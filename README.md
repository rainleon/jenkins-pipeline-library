# jenkins-pipeline-library
jenkins pipeline shared-libraries


## 使用说明

> 用来维护pileline的公共lib库,复用基础的部署代码.


1. 加载全局pipeline库

        library identifier: 'jenkins-pipeline-library@master',
        retriever: modernSCM([$class: 'GitSCMSource', remote: "ssh://git@xxxxxxx/infra/jenkins-pipeline-library.git",
                              credentialsId: "ssh_key_name"])
2. 使用相关Util方法

        //获取指定目录下的项目列表
        Utilities.getProjectList("${workspace}")
        //构建输入表单
        Utilities.getInputParam(project, Config.DEF_PROJEVT_VERSION, "$JOB_BASE_NAME", getEnv("$JOB_NAME",Config.DEF_ENV))
        //下载项目
        DeployUtil.downloadArtifact(paramMap, "${workspace}/${paramMap.PROJECT}")
        //生成发布任务
        Utilities.generateBranch(paramMap, ipNode)


## 相关文档

- [ABOUT_JENKINS2.0_WITH_PIPELINE](src/site/markdown/ABOUT_JENKINS2.0_WITH_PIPELINE.md)
- [ABOUT_PIPELINE](src/site/markdown/ABOUT_PIPELINE.md)


## 参考资料

- [shared-libraries官方指南](https://jenkins.io/doc/book/pipeline/shared-libraries/#loading-resources)