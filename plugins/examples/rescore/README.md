# 精排demo

## 使用方法
[1] 参考1：https://bbs.huaweicloud.com/blogs/257255
[2] 打包 + 运行插件
gradle 的build命令 得到： example-rescore-7.6.0-SNAPSHOT.zip
将该包解压到home/plugins/example-rescore下，此时该目录下的文件如下：
example-rescore-7.6.0-SNAPSHOT.jar
LICENSE.txt
NOTICE.txt
plugin-descriptor.properties

## 验证操作

### 插入数据

POST http://localhost:9200/test/_doc/1
Content-Type: application/json

~~~ json
{
"test_field1": 1,
"test_field2": 3
}
~~~

POST http://localhost:9200/test/_doc/2
Content-Type: application/json

~~~ json
{
"test_field1": 2,
"test_field2": 2
}
~~~

POST http://localhost:9200/test/_doc/3
Content-Type: application/json

~~~ json
{
"test_field1": 3,
"test_field2": 1
}
~~~

### 检索数据

GET http://localhost:9200/test/_search
Content-Type: application/json

~~~ json
{
	"query": {
		"match_all": {}
	},
	"rescore": {
		"example": {
			"factor": 3,
			"factor_field": "test_field2"
		},
		"window_size": 2
	}
}
~~~
