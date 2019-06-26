#!/usr/bin/env groovy
/**
 * 部署jar包到服务器,这里部署可执行的jar,通过自定义的命令行启动
 * @param paramMap
 * @param ip
 * @param workspace
 * @param sshKeyParam
 * @return
 */
def deployJar(paramMap, deployParam, workspace, sshKeyParam) {
  if (!paramMap.PROJECT || !paramMap.ENV || !paramMap.DEPLOY_USER) {
    error "param is required! as PROJECT=${paramMap.PROJECT}, ENV=${paramMap.ENV}"
  }

  def ip = deployParam["IP"]
  def nodeFile = deployParam["ALIAS"] ? deployParam["IP"] + "-" + deployParam["ALIAS"] : deployParam["IP"]
  def COMMON_ENV = "${paramMap.PROJECT}/environments/${paramMap.ENV}/common_env"
  def CONFIG_FILE = "${paramMap.PROJECT}/environments/${paramMap.ENV}/${nodeFile}"
  def REMOTE_USER = "${paramMap.DEPLOY_USER}"
  def WORK_DIR = "${paramMap.WORK_DIR}"
  def jarName = "${paramMap.ARTIFACT_NAME}"

  println("*. 准备部署服务:${paramMap.PROJECT} on ${REMOTE_USER}@${ip}")
  println '=================Generate deploy script:===================='

  concatDeploySh("${workspace}/${COMMON_ENV}", "${workspace}/${ip}.sh")
  concatDeploySh("${workspace}/${CONFIG_FILE}", "${workspace}/${ip}.sh")
  sh "echo 'export PROJECT_NAME=${paramMap.PROJECT} \nexport PROJECT_VERSION=${paramMap.PROJECT_VERSION} \nexport " +
      "PROJECT_ENV=${paramMap.ENV} \nexport WORK_DIR=${paramMap.WORK_DIR} ' >>  ${workspace}/${ip}.sh"
  concatDeploySh("${workspace}/${paramMap.PROJECT}/deploy.sh", "${workspace}/${ip}.sh")
  println "=================Check script:===================="

  sh "cat ${workspace}/${ip}.sh"

  println "=================SCopy script & artifact:===================="

  // 初始化目
  String date = new Date().format('yyyy-MM-dd_HH-mm');
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/tmp; " +
      "then mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/tmp; else rm -rf ${WORK_DIR}/${paramMap.PROJECT}/tmp/*; fi;\""
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/backup/$date; " +
      "then mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/backup/$date; fi;\""
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/rollback; then mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/rollback; fi;\""
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\'if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/work; then mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/work; " +
      "elif [ \"`ls -A ${WORK_DIR}/${paramMap.PROJECT}/work/`\" != \"\" ];then cp -R ${WORK_DIR}/${paramMap.PROJECT}/work/* " +
      "${WORK_DIR}/${paramMap.PROJECT}/backup/$date/; else echo \"1\"; fi;\'"

  sh "scp " + "${sshKeyParam}".replaceAll(" -p ", " -P ") + " -o StrictHostKeyChecking=no " +
      "-r ${workspace}/${ip}.sh ${REMOTE_USER}@${ip}:${WORK_DIR}/${paramMap.PROJECT}/tmp/${ip}.sh"
  sh "scp " + "${sshKeyParam}".replaceAll(" -p ", " -P ") + " -o StrictHostKeyChecking=no " +
      "-r ${workspace}/${paramMap.PROJECT}/${jarName} ${REMOTE_USER}@${ip}:${WORK_DIR}/${paramMap.PROJECT}/tmp/${jarName}"

  println "=================Execute script:===================="
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"rm -rf ${WORK_DIR}/${paramMap.PROJECT}/work/* " +
      "&& mv ${WORK_DIR}/${paramMap.PROJECT}/tmp/${jarName} ${WORK_DIR}/${paramMap.PROJECT}/work/" +
      "&& mv ${WORK_DIR}/${paramMap.PROJECT}/tmp/${ip}.sh ${WORK_DIR}/${paramMap.PROJECT}/work/ \""

  checkAndStop(paramMap, ip, REMOTE_USER, sshKeyParam)

  sleep 5
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"source ${WORK_DIR}/${paramMap.PROJECT}/work/${ip}.sh && start \""

  println "=================Health check:===================="
  def pid = sh script: "ssh ${sshKeyParam} ${REMOTE_USER}@${ip} \"ps ax | grep -i '${paramMap.PROJECT_ARTIFACT_ID}'" +
      " |grep java | grep -v grep | awk '{print \\\$1}' \"", returnStdout: true

  // need config health api in environment.json
  if (paramMap.HEALTH_API != null && paramMap.HEALTH_API != "") {
    println "=================Service validate:===================="
    checkWaitforit()
    HEALTH_URL = "${paramMap.HEALTH_API}".replace("#host#", ip)
    println "$HOME/waitforit -full-connection=${HEALTH_URL} -timeout=300"
    sh "\$HOME/waitforit -full-connection=${HEALTH_URL} -timeout=300"
  }
  println("*. 部署服务完毕:${paramMap.PROJECT} on ${REMOTE_USER}@${ip}，as pid is ${pid} \n\n\n")
}

/**
 * 部署tar包,压缩包包含指定目录格式的文件,需要自己定义实现启停脚本
 * @param paramMap
 * @param ip
 * @param workspace
 * @param sshKeyParam
 * @return
 */
def deployTar(paramMap, deployParam, workspace, sshKeyParam) {
  if (!paramMap.PROJECT || !paramMap.ENV) {
    error "param is required! as PROJECT=${paramMap.PROJECT}, ENV=${paramMap.ENV}"
  }
  def ip = deployParam["IP"]
  def nodeFile = deployParam["ALIAS"] ? deployParam["IP"] + "-" + deployParam["ALIAS"] : deployParam["IP"]
  def COMMON_ENV = "${paramMap.PROJECT}/environments/${paramMap.ENV}/common_env"
  def CONFIG_FILE = "${paramMap.PROJECT}/environments/${paramMap.ENV}/${nodeFile}"
  def REMOTE_USER = "${paramMap.DEPLOY_USER}"
  def WORK_DIR = "${paramMap.WORK_DIR}"
  def tarName = "${paramMap.ARTIFACT_NAME}"
  def tarNameNoType = "${paramMap.ARTIFACT_NAME}".replace("-${paramMap.PROJECT_CLASSIFIER}.${paramMap.PROJECT_TYPE}", "")

  println("*. 准备部署服务:${paramMap.PROJECT} on ${REMOTE_USER}@${ip}")
  println '=================Generate deploy script:===================='

  println '*. 开始生成配置文件 '
  concatDeploySh("${workspace}/${COMMON_ENV}", "${workspace}/${ip}.sh")
  concatDeploySh("${workspace}/${CONFIG_FILE}", "${workspace}/${ip}.sh")
  sh "echo 'export PROJECT_NAME=${paramMap.PROJECT} \nexport PROJECT_VERSION=${paramMap.PROJECT_VERSION} \nexport " +
      "PROJECT_ENV=${paramMap.ENV} \nexport PROJECT_JVM_APP_PARAM=${paramMap.PROJECT_JVM_APP_PARAM} " +
      "\nexport WORK_DIR=${paramMap.WORK_DIR} ' >>  ${workspace}/${ip}.sh"
  concatDeploySh("${workspace}/${paramMap.PROJECT}/deploy.sh", "${workspace}/${ip}.sh")
  println "=================Check script:===================="
  sh "cat ${workspace}/${ip}.sh"

  println "=================SCopy script & artifact:===================="

  // 初始化目
  String date = new Date().format('yyyy-MM-dd_HH-mm');
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/tmp; " +
      "then mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/tmp; else rm -rf ${WORK_DIR}/${paramMap.PROJECT}/tmp/*; fi;\""
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/backup/$date; " +
      "then mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/backup/$date; fi;\""
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/rollback; then mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/rollback; fi;\""
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\'if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/work; then mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/work; " +
      "elif [ \"`ls -A ${WORK_DIR}/${paramMap.PROJECT}/work/`\" != \"\" ];then cp -R ${WORK_DIR}/${paramMap.PROJECT}/work/* " +
      "${WORK_DIR}/${paramMap.PROJECT}/backup/$date/; else echo \"1\"; fi;\'"

  sh "scp " + "${sshKeyParam}".replaceAll(" -p ", " -P ") + " -o StrictHostKeyChecking=no " +
      "-r ${workspace}/${ip}.sh ${REMOTE_USER}@${ip}:${WORK_DIR}/${paramMap.PROJECT}/tmp/${ip}.sh"
  sh "scp " + "${sshKeyParam}".replaceAll(" -p ", " -P ") + " -o StrictHostKeyChecking=no " +
      "-r ${workspace}/${paramMap.PROJECT}/${tarName} ${REMOTE_USER}@${ip}:${WORK_DIR}/${paramMap.PROJECT}/tmp/${tarName}"

  println "=================Execute script:===================="
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"rm -rf ${WORK_DIR}/${paramMap.PROJECT}/work/* " +
      "&& mv ${WORK_DIR}/${paramMap.PROJECT}/tmp/${tarName} ${WORK_DIR}/${paramMap.PROJECT}/work/" +
      "&& mv ${WORK_DIR}/${paramMap.PROJECT}/tmp/${ip}.sh ${WORK_DIR}/${paramMap.PROJECT}/work/ " +
      "&& cd ${WORK_DIR}/${paramMap.PROJECT}/work/ && tar -zxvf ${tarName}" +
      "&& mv ${tarNameNoType}/* ./ && rm -rf ${tarNameNoType} \""

//    checkAndStop(paramMap, ip, REMOTE_USER, sshKeyParam)
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\". ${WORK_DIR}/${paramMap.PROJECT}/work/${ip}.sh && stop || echo stop \""

//    pid = sh script: "ssh ${sshKeyParam} ${REMOTE_USER}@${ip} \"ps ax | grep -i '${paramMap.PROJECT_ARTIFACT_ID}' |grep java " +
//            "| grep -v grep | awk '{print \\\$1}' \"", returnStdout: true
  // TODO check service if has bean started or not,and retry
  sleep 5
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\". ${WORK_DIR}/${paramMap.PROJECT}/work/${ip}.sh && start \""

  println "=================Health check:===================="
  def pid = sh script: "ssh ${sshKeyParam} ${REMOTE_USER}@${ip} \"ps ax | grep -i '${paramMap.PROJECT_ARTIFACT_ID}'" +
      " |grep java | grep -v grep | awk '{print \\\$1}' \"", returnStdout: true

  // need config health api in environment.json
  if (paramMap.HEALTH_API != null && paramMap.HEALTH_API != "") {
    println "=================Service validate:===================="
    checkWaitforit()
    HEALTH_URL = "${paramMap.HEALTH_API}".replace("#host#", ip)
    println "$HOME/waitforit -full-connection=${HEALTH_URL} -timeout=300"
    sh "\$HOME/waitforit -full-connection=${HEALTH_URL} -timeout=300"
  }
  println("*. 部署服务完毕:${paramMap.PROJECT} on ${REMOTE_USER}@${ip}，as pid is ${pid} \n\n\n")
}

/**
 * 检查是否停止
 * @param paramMap
 * @param ip
 * @param REMOTE_USER
 * @param sshKeyParam
 */
def checkAndStop(paramMap, ip, REMOTE_USER, sshKeyParam) {
  def pid;
  for (int i = 0; i < 300; i++) {
    sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
        "\"source ${paramMap.WORK_DIR}/${paramMap.PROJECT}/work/${ip}.sh && stop \""
    pid = sh script: "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
        "\"source ${paramMap.WORK_DIR}/${paramMap.PROJECT}/work/${ip}.sh && check \"", returnStdout: true
    println("checkAndStop result =${pid}=")
    if ("${pid}".trim() == "") {
      return
    }
    sleep 3
    println("checkAndStop will retry ${i}")
  }
  error("stop process failed! for pid = ${pid}")
}

/**
 * 部署War包, 部署war包,
 * @param paramMap
 * @param ip
 * @param workspace
 * @param sshKeyParam
 * @return
 */
def deployWar(paramMap, deployParam, workspace, sshKeyParam) {
  if (!paramMap.PROJECT || !paramMap.ENV) {
    error "param is required! as PROJECT=${paramMap.PROJECT}, ENV=${paramMap.ENV}"
  }
  def ip = deployParam["IP"]
  def nodeFile = deployParam["ALIAS"] ? deployParam["IP"] + "-" + deployParam["ALIAS"] : deployParam["IP"]
  def COMMON_ENV = "${paramMap.PROJECT}/environments/${paramMap.ENV}/common_env"
  def CONFIG_FILE = "${paramMap.PROJECT}/environments/${paramMap.ENV}/${nodeFile}"
  def TOMCAT_CONFIG_FILE = "${paramMap.PROJECT}/environments/${paramMap.ENV}/tomcat/*"
  def REMOTE_USER = "${paramMap.DEPLOY_USER}"
  def WORK_DIR = "${paramMap.WORK_DIR}"
  def warName = "${paramMap.ARTIFACT_NAME}"
  def warNameNoType = "${paramMap.ARTIFACT_NAME}".replace("-${paramMap.PROJECT_CLASSIFIER}.${paramMap.PROJECT_TYPE}", "")


  println("*. 准备部署服务:${paramMap.PROJECT} on ${REMOTE_USER}@${ip}")
  println '=================Generate deploy script:===================='
  println '*. 开始生成部署脚本 '

  concatDeploySh("${workspace}/${COMMON_ENV}", "${workspace}/${ip}.sh")
  concatDeploySh("${workspace}/${CONFIG_FILE}", "${workspace}/${ip}.sh")
  concatDeploySh("${workspace}/${paramMap.PROJECT}/deploy.sh", "${workspace}/${ip}.sh")

  sh "cat ${workspace}/${ip}.sh"

  println "=================SCopy script & artifact:===================="

  // 初始化目
  String date = new Date().format('yyyy-MM-dd_HH-mm');
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/tmp/tomcat; then " +
      "mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/tmp/tomcat; " +
      "else rm -rf ${WORK_DIR}/${paramMap.PROJECT}/tmp/*; " +
      "mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/tmp/tomcat; fi;\""

  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/backup/$date; " +
      "then mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/backup/$date; fi;\""
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/rollback; then mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/rollback; fi;\""
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\'if ! test -d ${WORK_DIR}/${paramMap.PROJECT}/work; then mkdir -p ${WORK_DIR}/${paramMap.PROJECT}/work; " +
      "elif [ \"`ls -A ${WORK_DIR}/${paramMap.PROJECT}/work/`\" != \"\" ];then cd ${WORK_DIR}/${paramMap.PROJECT}/work/ && " +
      "ls |grep -v upload | xargs -i cp -R {} " +
      "${WORK_DIR}/${paramMap.PROJECT}/backup/$date/; else echo \"1\"; fi;\'"

  sh "scp " + "${sshKeyParam}".replaceAll(" -p ", " -P ") + " -o StrictHostKeyChecking=no " +
      "-r ${workspace}/${ip}.sh ${REMOTE_USER}@${ip}:${WORK_DIR}/${paramMap.PROJECT}/tmp/${ip}.sh"
  sh "scp " + "${sshKeyParam}".replaceAll(" -p ", " -P ") + " -o StrictHostKeyChecking=no " +
      "-r ${workspace}/${paramMap.PROJECT}/${warName} ${REMOTE_USER}@${ip}:${WORK_DIR}/${paramMap.PROJECT}/tmp/${warName}"
  sh "scp " + "${sshKeyParam}".replaceAll(" -p ", " -P ") + " -o StrictHostKeyChecking=no " +
      "-r ${workspace}/${TOMCAT_CONFIG_FILE} ${REMOTE_USER}@${ip}:${WORK_DIR}/${paramMap.PROJECT}/tmp/tomcat/"

  println "=================Execute script:===================="

  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\" cd ${WORK_DIR}/${paramMap.PROJECT}/work/ && ls | grep -v upload | xargs rm -rf  \""
  println "=================mv war:===================="
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\" mv ${WORK_DIR}/${paramMap.PROJECT}/tmp/${warName} ${WORK_DIR}/${paramMap.PROJECT}/work/ \""
  println "=================mv sh:===================="
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\" mv ${WORK_DIR}/${paramMap.PROJECT}/tmp/${ip}.sh ${WORK_DIR}/${paramMap.PROJECT}/work/ \""
  println "=================chmod x:===================="
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\" cd ${WORK_DIR}/${paramMap.PROJECT}/work/ && chmod +x ${ip}.sh \""
  println "=================jar -xvf war:===================="
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\" cd ${WORK_DIR}/${paramMap.PROJECT}/work/ && . ./${ip}.sh && jar -xvf ${warName} \""


  println "=================prepareContainer:===================="
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\". ${WORK_DIR}/${paramMap.PROJECT}/work/${ip}.sh && prepareContainer \""

  println "=================stop tomcat:===================="
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\". ${WORK_DIR}/${paramMap.PROJECT}/work/${ip}.sh && stop \""

  sleep 5
  println "=================start tomcat:===================="
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\". ${WORK_DIR}/${paramMap.PROJECT}/work/${ip}.sh && start \""

  println "=================Health check:===================="
  def pid = sh script: "ssh ${sshKeyParam} ${REMOTE_USER}@${ip} " +
      "\". ${WORK_DIR}/${paramMap.PROJECT}/work/${ip}.sh && check \"", returnStdout: true

  // need config health api in environment.json
  if (paramMap.HEALTH_API != null && paramMap.HEALTH_API != "") {
    println "=================Service validate:===================="
    checkWaitforit()
    HEALTH_URL = "${paramMap.HEALTH_API}".replace("#host#", ip)
    println "$HOME/waitforit -full-connection=${HEALTH_URL} -timeout=300"
    sh "\$HOME/waitforit -full-connection=${HEALTH_URL} -timeout=300"
  }
  println("*. 部署服务完毕:${paramMap.PROJECT} on ${REMOTE_USER}@${ip}，as pid is ${pid} \n\n\n")
}

/**
 * 回滚部署,此处回滚的是前一个事件里备份的内容,回滚历史版本
 * @param paramMap
 * @param ip
 * @param workspace
 * @param sshKeyParam
 */
static def rollbackJar(paramMap, ip, workspace, sshKeyParam) {
  // todo rollback

}

/**
 * 下载构建产物
 * @param paramMap
 * @param path
 */
String downloadArtifact(paramMap, path) {
  def nexusUrl = paramMap.PROJECT_NEXUS_URL
  def groupPath = "${paramMap.PROJECT_GROUP_ID}".replace(".", "/")
  def artifact = paramMap.PROJECT_ARTIFACT_ID
  def version = paramMap.PROJECT_VERSION
  def classifier
  if (paramMap.PROJECT_CLASSIFIER_BY_ENV == "true") {
    classifier = "-${paramMap.ENV}"
  } else {
    classifier = paramMap.PROJECT_CLASSIFIER ? "-${paramMap.PROJECT_CLASSIFIER}" : ""
  }
  def type = paramMap.PROJECT_TYPE
  def newVersion = version

  def projectUrlPrefic = "${nexusUrl}/${groupPath}/${artifact}"
  println "projectUrlPrefic=${projectUrlPrefic}"
  // snapshot版本需要获取真实的版本号
  if ("maven-snapshots".equalsIgnoreCase("${paramMap.PROJECT_NEXUS_REPO}")) {
    print "curl -H \'Cache-Control: no-cache\' -t utf-8 -s -L -o ${path}/artifactVersion.xml " +
        "${projectUrlPrefic}/${version}/maven-metadata.xml"
    sh "curl -H \'Cache-Control: no-cache\' -t utf-8 -s -L -o ${path}/artifactVersion.xml " +
        "${projectUrlPrefic}/${version}/maven-metadata.xml"
    def scriptStr = "grep -m 1 \\<value\\> ${path}/artifactVersion.xml | sed -e " +
        "'s/<value>\\(.*\\)<\\/value>/\\1/' | sed -e 's/ //g'"

    newVersion = sh returnStdout: true, script: scriptStr
    newVersion = "${newVersion}".trim()
  }

  def fileName = "${artifact}-${version}${classifier}.${type}"
  nexusUrl = "${nexusUrl}/${groupPath}/${artifact}/${version}/${artifact}-${newVersion}${classifier}.${type}"
  println("curl -H \'Cache-Control: no-cache\' -t utf-8 -s -L -o ${path}/${fileName} ${nexusUrl}")
  sh "curl -H \'Cache-Control: no-cache\' -t utf-8 -s -L -o \'${path}/${fileName}\' ${nexusUrl}"

  // check sha1 value
  def sha1sum = sh returnStdout: true, script: "sha1sum ${path}/${fileName}|awk '{print \$1}'"
  def sha1sumNexus = sh returnStdout: true, script: "curl --silent ${nexusUrl}.sha1"
  if (!sha1sum || !sha1sumNexus || !"${sha1sum}".trim().equals("${sha1sumNexus}".trim())) {
    println(" sha1sum != sha1sumNexus : ${sha1sum} != ${sha1sumNexus}")
    error "the artifact: ${nexusUrl} is broken,please check that for security!"
  }

  return fileName
}

/**
 * 以docker-compose方式部署Docker镜像
 * @param paramMap
 * @param ip
 * @param workspace
 * @param sshKeyParam
 * @return
 */
@Deprecated
def deployDockerCompose(paramMap, deployParam, workspace, sshKeyParam) {
  def COMMON_ENV = "${paramMap.PROJECT}/environments/${paramMap.ENV}/common_env"
  def CONFIG_FILE = "${paramMap.PROJECT}/environments/${paramMap.ENV}/${ip}"
  def DOCKER_FILE = "${paramMap.PROJECT}/${paramMap.COMPOSE_FILE_NAME}"
  def ip = deployParam["IP"]
  def nodeFile = deployParam["ALIAS"] ? deployParam["IP"] + "-" + deployParam["ALIAS"] : deployParam["IP"]
  def REMOTE_USER = "root"
  println("*. 准备部署服务:${paramMap.PROJECT} on ${REMOTE_USER}@${nodeFile}")

  println '=================Generate script:===================='
  sh "echo 'set -e' > ${workspace}/${ip}.sh"

  if (fileExists("${workspace}/${COMMON_ENV}")) {
    println '*. 开始复制公共配置 '
    sh "cat ${workspace}/${COMMON_ENV} >> ${workspace}/${ip}.sh"
  }
  sh "cat ${workspace}/${CONFIG_FILE} >> ${workspace}/${ip}.sh"
  sh "echo \" \" >> ${workspace}/${ip}.sh"
  sh "echo \"export PROJECT_VERSION=${paramMap.PROJECT_VERSION} \" >> ${workspace}/${ip}.sh"
  sh "echo \"docker-compose -f /tmp/$DOCKER_FILE down --remove-orphans \" >> ${workspace}/${ip}.sh"
  sh "echo \"docker-compose -f /tmp/$DOCKER_FILE pull\" >> ${workspace}/${ip}.sh"
  sh "echo \"docker-compose -f /tmp/$DOCKER_FILE up -d\" >> ${workspace}/${ip}.sh"

  println "=================Check script:===================="
  sh "cat ${workspace}/${ip}.sh"

  println "=================Copy script:===================="
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"if ! test -d /tmp/${paramMap.PROJECT}; " +
      "then mkdir /tmp/${paramMap.PROJECT}; else rm -rf /tmp/${paramMap.PROJECT}/*; fi;\""
  sh "scp " + "${sshKeyParam}".replaceAll(" -p ", " -P ") + " -o StrictHostKeyChecking=no " +
      "~/.docker/config.json ${REMOTE_USER}@${ip}:~/.docker/config.json"
  sh "scp " + "${sshKeyParam}".replaceAll(" -p ", " -P ") + " -o StrictHostKeyChecking=no " +
      "-r ${workspace}/${DOCKER_FILE} ${REMOTE_USER}@${ip}:/tmp/${paramMap.PROJECT}/"
  sh "scp " + "${sshKeyParam}".replaceAll(" -p ", " -P ") + " -o StrictHostKeyChecking=no " +
      "-r ${workspace}/${ip}.sh ${REMOTE_USER}@${ip}:/tmp/${paramMap.PROJECT}/${ip}.sh"

  println "=================Execute script:===================="
  sh "ssh ${sshKeyParam} -o StrictHostKeyChecking=no ${REMOTE_USER}@${ip} " +
      "\"cat /tmp/${paramMap.PROJECT}/${ip}.sh && sh /tmp/${paramMap.PROJECT}/${ip}.sh\""
  def containerId = sh script: "ssh ${sshKeyParam} ${REMOTE_USER}@${ip} \"docker ps | grep ${paramMap.PROJECT}:${paramMap.PROJECT_VERSION}|awk '{print \\\$1}'\"", returnStdout: true

  // need config health api in environment.json
  if (paramMap.HEALTH_API != null && paramMap.HEALTH_API != "") {
    println "=================Service validate:===================="
    checkWaitforit()
    HEALTH_URL = "${paramMap.HEALTH_API}".replace("#host#", ip)
    sh "\$HOME/waitforit -full-connection=${HEALTH_URL} -timeout=300"
  }
  println("*. 部署服务完毕:${paramMap.PROJECT} on ${REMOTE_USER}@${ip}，as containerId is ${containerId} \n\n\n")
}

/**
 * 检查是否安装了waitforit组件,没有得话,进行安装.
 * @return
 */
def checkWaitforit() {
  sh '''
        set -e
        if [ ! -f $HOME/waitforit ]; then
            echo "Prepare for downloading waitforit from https://github.com/maxcnunes/waitforit/releases/download/v1.4.0/waitforit-linux_amd64,please wait patiently! "
            url=" https://github.com/maxcnunes/waitforit/releases/download/v1.4.0/waitforit-linux_amd64"
            curl -L -o $HOME/waitforit "${url}"
            chmod 755 $HOME/waitforit
        fi
    '''
}

/**
 * 将文件1的内容追加到文件2
 * @param fileSource
 * @param fileTarget
 */
def concatDeploySh(GString fileSource, GString fileTarget) {
  if (fileExists("${fileSource}")) {
    sh "cat ${fileSource} >> ${fileTarget}"
    sh "echo \"\n\n \" >>${fileTarget}"
  }
}

/**
 * 解压缩
 * @param paramMap
 * @param path
 * @param fileName
 * @param subDir
 * @return
 */
String extractArtifact(paramMap, path, fileName, subDir) {
  def type = paramMap.PROJECT_TYPE
  def classifier
  if (paramMap.PROJECT_CLASSIFIER_BY_ENV == "true") {
    classifier = "-${paramMap.ENV}"
  } else {
    classifier = paramMap.PROJECT_CLASSIFIER ? "-${paramMap.PROJECT_CLASSIFIER}" : ""
  }
  def fileNameWithOutType = "${fileName}".replace(".${type}", "").replace("${classifier}", "")
  if (type == "tar.gz") {
    sh "cd ${path} && tar -zxvf ${fileName} && mv ${fileNameWithOutType} ${subDir}"
  } else if (type == "war") {
    sh "mkdir -p ${path}/${subDir} && cd ${path}/${subDir} && jar -xvf ../${fileName} "
  }
  return subDir
}

/**
 * 比较两个目录的文件列表,目前主要对比lib目录
 * @param paramMap 参数
 * @param path 根路径
 * @param dxDir 新版本的路径
 * @param oldDir 旧版本的路径
 * @return
 */
@NonCPS
def diffFile(paramMap, path, dxDir, oldDir) {
  dxDir = path + "/" + dxDir
  oldDir = path + "/" + oldDir

  def libDir = sh returnStdout: true, script: "cd ${dxDir} && find . -name \"lib\" -type d"
  println "lib dir is ${dxDir}/${libDir}"


  def scriptStr = "ls -Am ${dxDir}/${libDir} | awk '{printf \$0}' | sed 's/,//g' "
  def libFiles = sh returnStdout: true, script: scriptStr
  libFiles.split(" ").each {
    if (fileExists("${dxDir}/${libDir}/${it}")) {
      if (fileExists("$oldDir/${libDir}/${it}")) {
        def sha1sumNew = sh returnStdout: true, script: "sha1sum ${dxDir}/${libDir}/${it}|awk '{print \$1}'"
        def sha1sumOld = sh returnStdout: true, script: "sha1sum $oldDir/${libDir}/${it}|awk '{print \$1}'"
        if (sha1sumNew && sha1sumOld && "${sha1sumNew}".trim().equals("${sha1sumOld}".trim())) {
          sh "rm -rf ${dxDir}/${libDir}/${it} && rm -rf $oldDir/${libDir}/${it} "
        } else {
          sh "echo \"modify ${it}\" >> ${dxDir}/${libDir}/changelog.txt"
          sh "rm -rf $oldDir/${libDir}/${it} "
        }
      } else {
        sh "echo \"add ${it}\" >> ${dxDir}/${libDir}/changelog.txt"
      }
    }
  }
  scriptStr = "ls -Am ${oldDir}/${libDir} | awk '{printf \$0}' | sed 's/,//g' "
  libFiles = sh returnStdout: true, script: scriptStr
  libFiles.split(" ").each {
    if (fileExists("$oldDir/${libDir}/${it}")) {
      if (!fileExists("${dxDir}/${libDir}/${it}")) {
        sh "echo \"delete ${it}\" >> ${dxDir}/${libDir}/changelog.txt"
        sh "rm -rf $oldDir/${libDir}/${it} "
      }
    }
  }

  sh "cd ${dxDir} && ls ${libDir}/ "
  sh "cd ${oldDir} && ls ${libDir}/ "

  return dxDir
}


