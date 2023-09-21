idea编译参考文章：[教你编译调试Elasticsearch 6.3.2源码-腾讯云开发者社区-腾讯云](https://cloud.tencent.com/developer/article/1385924)

源码分析参考文章：

* [【Elasticsearch源码】查询源码分析](https://blog.csdn.net/wudingmei1023/article/details/103968625)
* [lucene源码分析](https://www.6aiq.com/member/Emma?p=2)
* [SpanQuery源码学习总结](https://cloud.tencent.com/developer/article/1936235)
* [SpanNearQuery（一）](https://blog.csdn.net/iteye_14612/article/details/82677774)
* [SpanQuery跨度查询](https://developer.aliyun.com/article/45341)
* [PhraseQuery 的 slop 的理解](https://my.oschina.net/wangguolongnk/blog/107690)
* [lucene的使用](https://blog.csdn.net/z_ssyy/article/details/105234446)
* [lucene索引结构](https://zhuanlan.zhihu.com/p/443722667)
* [倒排表存储](https://juejin.cn/post/7123923721881911304)
* [lucene源码系列](https://www.6aiq.com/member/Emma?p=1)
* [书籍](https://github.com/reburning/book/blob/master/%E6%8E%A8%E8%8D%90%E7%B3%BB%E7%BB%9F%E5%AE%9E%E8%B7%B5.pdf)
* [lucene搜索过程分析(重点看)](https://cloud.tencent.com/developer/article/1163393)

# 必要条件：

- VPN（可避免很多奇怪的问题）
- JDK版本：（必须）[13.0.2](https://www.oracle.com/java/technologies/javase/jdk13-archive-downloads.html)
- gradle 6.1.1  可以由编译时自动下载（可选）

## 本地环境部署：

### JDK13.0.2安装：

本次安装的是：jdk-13.0.2_windows-x64_bin.zip（绿色版）

由于我本机先安装了jdk8，后面才安装jdk13，会导致cmd 上执行java -version显示的是：jdk13。

处理方式：系统环境变量 》path》会看到以下配置，一个是jdk13，另一个是jdk，屏蔽其中一个即可来回切换不同的JDK版本！！！

C:\Program Files (x86)\Common Files\Oracle\Java\javapath

C:\ProgramData\Oracle\Java\javapath

（注）：建议以上两个目录都删除或！！！[因为以上两个目录是自动配置java环境程序配置](https://baijiahao.baidu.com/s?id=1663285105466706416&wfr=spider&for=pc)的。。。

（注）：elasticsearch-7.6.0要求必须是：JDK13，即保证当前环境是JDK10。

#### 环境变量

elasticsearch-7.6.0要求JAVA_HOME必须指向JDK13。。。

即java -version执行结果必须是jdk13

### 下载代码

版本号：elasticsearch-7.6.0

下载源码： https://github.com/elastic/elasticsearch/releases?page=11

下载发行版： https://www.elastic.co/cn/downloads/past-releases/elasticsearch-7-6-0

### 复制插件：

在elasticsearch-7.6.0源码根目录下创建home目录。

将发行版中的modules 复制到 源码中home目录

将发行版中的config 复制到 源码中home目录

home目录案例：

G:\github\elasticsearch-7.6.0\home

### 配置文件

在 `home/config` 目录下新建 `java.policy` 文件，填入下面内容

```
grant {
 permission java.lang.RuntimePermission "createClassLoader";
};
```

### 源码Maven仓库地址

需要修改下列文件的 maven URL 配置：

- elasticsearch\build.gradle

```
allprojects {
  ....
  新增代码
  repositories {
    maven {
      url 'http://maven.aliyun.com/nexus/content/groups/public/'
    }
  }

}
```

- elasticsearch-7.6.0\benchmarks\build.gradle
- elasticsearch-7.6.0\client\benchmark\build.gradle

```
buildscript {
    repositories {
        maven {
            url 'http://maven.aliyun.com/nexus/content/groups/public/'
        }
    }
    dependencies {
        classpath 'com.github.jengelman.gradle.plugins:shadow:2.0.2'
    }
}
```

### gradle编译

在elasticsearch-7.6.0源码根目录下执行：gradlew.bat idea

#### 问题：

基本会报插件找不到，如hadoop不存在。终极方法，将hadoop_home系统变量暂时去掉。

### 导入idea

#### 配置项目JDK

选中项目》Project Structure》Project页面》SDK》选JDK10，即可。。。

#### 配置gradle路径:

导入idea后，配置gradle的路径在 elasticsearch-7.6.0源码中的gradle路径：G:/qzd/JavaProject/QZD_GROUP/openSource/elasticsearch-7.6.0/gradle ！！！

不配置的话，默认会下载到用户目录 ！！！

#### 配置gradle 的 Builder and Run

Build and run using 》选 IntelliJ IDEA

Run tests using 》选 IntelliJ IDEA

## Modify options

### 最终启动参数 vm options

-Des.path.conf=G:\elasticsearch-7.6.0\home\config
-Des.path.home=G:\elasticsearch-7.6.0\home
-Dlog4j2.disable.jmx=true
-Djava.security.policy=G:\elasticsearch-7.6.0\home\config\java.policy

选中Add dependencies with provided scope to classpath

然后启动，成功后，访问以下地址。。。。

http://localhost:9200/_cat/health

http://localhost:9200/

重点问题：

#### java.lang.NoClassDefFoundError: org/elasticsearch/plugins/ExtendedPluginsClassLoader 报错

[java.lang.NoClassDefFoundError: org/elasticsearch/plugins/ExtendedPluginsClassLoader 报错_我的男妈妈_歆语的博客-CSDN博客](https://blog.csdn.net/wodenanmama/article/details/120182411)

[后端 - Elasticsearch7.3.2源码环境搭建 - 个人文章 - SegmentFault 思否](https://segmentfault.com/a/1190000022217206)
