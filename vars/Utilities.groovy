#!/usr/bin/env groovy
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

/**
 * 生成并发部署的分支
 * @param paramMap
 * @param ipNode
 * @return
 */
def generateBranch(paramMap, ipNode) {
  return {
    node {
      step([$class: 'WsCleanup'])
      unstash "id_rsa"
      sh "chmod 400 id_rsa"
      def workspace = pwd()
      unstash "${paramMap.PROJECT}"
      sh "ls -la ${workspace}"
      def deployParam = generateSSHKeyParam(paramMap, ipNode)
      dir("${paramMap.PROJECT}") {
        deploy(paramMap, deployParam, workspace, "-i ${workspace}/id_rsa -p " + deployParam.PORT)
      }
    }
  }
}

//SSH增加端口参数
def generateSSHKeyParam(paramMap, ipNode) {
  def deployParam = [:]
  def ipNodeArray = "$ipNode".split(":").toList()
  if (ipNodeArray.size() > 1) {
    deployParam["IP"] = ipNodeArray[0]
    deployParam["PORT"] = ipNodeArray[1]
  } else {
    deployParam["IP"] = ipNode
    deployParam["PORT"] = "22"
  }

  if (paramMap.NODES_PORT) {
    for (node in paramMap.NODES_PORT) {
      if (!node["PORT"] || node["PORT"] == "") {
        node["PORT"] = "22"
      }

      if (node["HOST"] == deployParam["IP"] && node["PORT"] == deployParam["PORT"]) {
        deployParam["ALIAS"] = node["ALIAS"]
        if (node["WORK_DIR"]) {
          paramMap.WORK_DIR = node["WORK_DIR"]
        }
        break
      }
    }
  }

  return deployParam
}

/**
 * 部署操作,
 * @param paramMap
 * @param ip
 * @param workspace
 * @param sshKeyParam
 * @return
 */

def deploy(paramMap, deployParam, workspace, sshKeyParam) {
  println("=======new version for deploy!!!!")
  def deployObj = new DeployUtil()
  if (paramMap.PROJECT_TYPE == "jar") {
    return deployObj.deployJar(paramMap, deployParam, workspace, sshKeyParam)
  } else if (paramMap.PROJECT_TYPE == "docker-compose") {
    return deployObj.deployDockerCompose(paramMap, deployParam, workspace, sshKeyParam)
  } else if (paramMap.PROJECT_TYPE == "docker-k8s") {
    // TODO for docker k8s cluster deploy
  } else if (paramMap.PROJECT_TYPE == "tar.gz") {
    return deployObj.deployTar(paramMap, deployParam, workspace, sshKeyParam)
  } else if (paramMap.PROJECT_TYPE == "war") {
    return deployObj.deployWar(paramMap, deployParam, workspace, sshKeyParam)
  } else {
    println("not support this deploy type !please check config for your environment.json")
  }
  return null
}

/**
 * 生成全局部署参数
 * @param paramMap
 * @param fileContent
 * @return
 */
def generateParam(paramMap, fileContent) {
  def SPLIT_CHAR = ";"
  def SPLIT_PORT_CHAR = ":"
  println("--------------------${env}-----------------------")

  println("generateParam : paramMap=${paramMap},fileContent = ${fileContent}")
  paramMap.COMPOSE_FILE_NAME = parse(fileContent)["COMPOSE_FILE_NAME"] //docker-compose 方式部署的文件名
  paramMap.HEALTH_API = parse(fileContent)["HEALTH_API"]  //健康检查的入口地址
  paramMap.PROJECT_TYPE = parse(fileContent)["PROJECT_TYPE"] //项目类型,可选的值 jar docker-compose docker-k8s tar.gz war
  paramMap.PROJECT_CLASSIFIER = parse(fileContent)["PROJECT_CLASSIFIER"] //nexus上传的时候用的classifier
  paramMap.PROJECT_CLASSIFIER_BY_ENV = parse(fileContent)["PROJECT_CLASSIFIER_BY_ENV"] //是否使用env当作classfier false
  paramMap.PROJECT_GROUP_ID = parse(fileContent)["PROJECT_GROUP_ID"] //产物groupId
  paramMap.PROJECT_ARTIFACT_ID = parse(fileContent)["PROJECT_ARTIFACT_ID"] //产物id
  paramMap.PROJECT_NEXUS_URL_PREFIX = parse(fileContent)["PROJECT_NEXUS_URL"]

  calculateNexusInfo(paramMap);
  // generate nexus

  // if not given nodes ,will read from config file
  def NODES = []
  def NODES_PORT = []

  if (paramMap.ENV) {
    if (parse(fileContent)["REMOTE_HOSTS_PORT"] && parse(fileContent)["REMOTE_HOSTS_PORT"]["${paramMap.ENV}"]) {
      def list = parse(fileContent)["REMOTE_HOSTS_PORT"]["${paramMap.ENV}"]
      for (node in list) {
        NODES.push(node.HOST + (node.PORT ? ":" + node.PORT : ""))
//                增加额外的处理逻辑
        NODES_PORT.push(parse("{\"HOST\":\"" + node.HOST + "\",\"PORT\":\"" + (node.PORT ? node.PORT : "") + "\"," +
            "\"WORK_DIR\":\"" + (node.WORK_DIR ? node.WORK_DIR : "") + "\",\"ALIAS\":\"" + (node.ALIAS ? node.ALIAS : "") + "\"}"))
      }
    } else if (parse(fileContent)["REMOTE_HOSTS"]["${paramMap.ENV}"]) {
      NODES = parse(fileContent)["REMOTE_HOSTS"]["${paramMap.ENV}"]
    } else {
      println("=============error fileContent :" + parse(fileContent) + "==========")
    }

    paramMap.NODES_PORT = NODES_PORT
    paramMap.NODES = (paramMap.NODES && paramMap.NODES instanceof String) ? paramMap.NODES.split(SPLIT_CHAR).toList() : NODES
    paramMap.PRE_NODES = paramMap.PRE_NODES ? paramMap.PRE_NODES.split(SPLIT_CHAR).toList() : paramMap.NODES[0..0]
    paramMap.NODES = paramMap.NODES - paramMap.PRE_NODES
  } else {
    println("=============missing paramMap.ENV:" + paramMap.ENV + "==========")
  }
  println("=============after generate:" + paramMap.inspect() + "==========")
  return paramMap
}

/**
 * 根据版本号计算necus仓库地址
 * @param paramMap
 * @return
 */
def calculateNexusInfo(paramMap) {
  paramMap.PROJECT_NEXUS_REPO = "${paramMap.PROJECT_VERSION}".toLowerCase().endsWith("snapshot") ? "maven-snapshots" : "maven-releases"
  paramMap.PROJECT_NEXUS_URL = paramMap.PROJECT_NEXUS_URL_PREFIX + "/" + paramMap.PROJECT_NEXUS_REPO //nexusUrl

}

/**
 * 生成输入参数表单
 * @param project
 * @return
 */
def getInputParam(String project, String defVersion) {
  def projectListStr = project.replace(" ", "\n")
  return [
      [$class: 'ChoiceParameterDefinition', choices: projectListStr, description: '项目名称', name: 'PROJECT'],
      [$class: 'ChoiceParameterDefinition', choices: 'test\nstaging\nproduction\ndev', description: '环境', name: 'ENV'],
      string(defaultValue: "$defVersion", description: '项目版本号', name: 'PROJECT_VERSION', trim: true),
      string(defaultValue: '', description: '前置部署节点,多个用";"分隔', name: 'PRE_NODES', trim: true),
      string(defaultValue: '', description: '全部要部署的机器，并行部署,多个用";"分隔', name: 'NODES', trim: true),
      string(defaultValue: '', description: '自定义的JVM应用参数,通过main方法args接收', name: 'PROJECT_JVM_APP_PARAM', trim: true)
  ]
}
//生产环境
def getInputParam(String project, String defVersion, String defProject) {
  if (defProject && project.indexOf(defProject) >= 0) {
    //使用jenkinsJOb的名称
    return [
        [$class: 'ChoiceParameterDefinition', choices: defProject, description: '项目名称', name: 'PROJECT'],
        string(defaultValue: "$defVersion", description: '项目版本号', name: 'PROJECT_VERSION', trim: true),
        string(defaultValue: '', description: '前置部署节点,多个用";"分隔', name: 'PRE_NODES', trim: true),
        string(defaultValue: '', description: '全部要部署的机器，并行部署,多个用";"分隔', name: 'NODES', trim: true),
        string(defaultValue: '', description: '自定义的JVM应用参数,通过main方法args接收', name: 'PROJECT_JVM_APP_PARAM', trim: true)
    ]
  } else {
    def projectListStr = project.replace(" ", "\n")
    return [
        [$class: 'ChoiceParameterDefinition', choices: projectListStr, description: '项目名称', name: 'PROJECT'],
        string(defaultValue: "$defVersion", description: '项目版本号', name: 'PROJECT_VERSION', trim: true),
        string(defaultValue: '', description: '前置部署节点,多个用";"分隔', name: 'PRE_NODES', trim: true),
        string(defaultValue: '', description: '全部要部署的机器，并行部署,多个用";"分隔', name: 'NODES', trim: true),
        string(defaultValue: '', description: '自定义的JVM应用参数,通过main方法args接收', name: 'PROJECT_JVM_APP_PARAM', trim: true)
    ]
  }

}
//指定环境
def getInputParam(String project, String defVersion, String defProject, String defEnv) {
  //项目列表
  def choiceList = []
  if (defProject && project.indexOf(defProject) >= 0) {
    choiceList.add([$class: 'ChoiceParameterDefinition', choices: defProject, description: '项目名称', name: 'PROJECT'])
  } else {
    def projectListStr = project.replace(" ", "\n")
    choiceList.add([$class: 'ChoiceParameterDefinition', choices: projectListStr, description: '项目名称', name: 'PROJECT'])
  }

  //环境列表
  if (defEnv && defEnv.size() > 0) {
    choiceList.add([$class: 'ChoiceParameterDefinition', choices: defEnv, description: '环境', name: 'ENV'])
  } else {
    choiceList.add([$class: 'ChoiceParameterDefinition', choices: 'staging\ntest\ndev', description: '环境', name:
        'ENV'])
  }

  //其他
  choiceList.add(string(defaultValue: "$defVersion", description: '项目版本号', name: 'PROJECT_VERSION', trim: true))
  choiceList.add(string(defaultValue: '', description: '前置部署节点,多个用";"分隔', name: 'PRE_NODES', trim: true))
  choiceList.add(string(defaultValue: '', description: '全部要部署的机器，并行部署,多个用";"分隔', name: 'NODES', trim: true))
  choiceList.add(string(defaultValue: '', description: '自定义的JVM应用参数,通过main方法args接收', name: 'PROJECT_JVM_APP_PARAM', trim: true))

  return choiceList
}

/**
 * 参数校验.
 * @param paramMap map类型的参数组
 */
def validateParam(paramMap) {
  if (paramMap.PROJECT_VERSION && paramMap.ENV) {
    if ("${paramMap.ENV}".equals("production") && !"${paramMap.PROJECT_VERSION}".toLowerCase().endsWith("snapshot")) {
      return true
    } else if("${paramMap.ENV}".equalsIgnoreCase("test") ||
        "${paramMap.ENV}".equalsIgnoreCase("staging") || "${paramMap.ENV}".equalsIgnoreCase("dev")) {
      return true
    }
  }
  return false
}

/**
 * 获取项目列表,
 * 这里的project下拉选项是动态从deploy的项目里获取生成的
 * @param dirName
 * @return
 */
@NonCPS
def getProjectList(dirName) {
  // 这里排除了src目录，如果有其他的需要排除的dir，增加a["dir_name"]=1 即可;
  def scriptStr = "ls -l " + dirName + " |awk 'BEGIN{a[\"src\"]=1;a[\"hooks\"]=1} /^d/ {if(a[\$NF]!=1){printf \$NF\" \"}}'"
  def result = sh returnStdout: true, script: scriptStr
  return result
}

/**
 * Json转字符串
 * @param param
 * @return
 */
@NonCPS
def from(param) {
  builder = new JsonBuilder()
  builder(param)
  return builder.toString()
}

/**
 * json字符串转换为json对象
 * @param text
 * @return
 */
@NonCPS
def parse(text) {
//    return new JsonSlurper().parseText(text)
  def object = new JsonSlurper().parseText(text)
  if (object instanceof groovy.json.internal.LazyMap) {
    return new HashMap<>(object)
  }
  return object
}
