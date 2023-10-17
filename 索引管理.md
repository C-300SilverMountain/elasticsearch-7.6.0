# [ElasticSearch](https://www.elastic.co/guide/en/elasticsearch/reference/current/removal-of-types.html) 7.x 默认不在支持指定索引类型

## 创建索引错误请求

PUT http://localhost:9200/accounts
Content-Type: application/json

```json
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "person":{
      "user":{
        "type":"text"
      },
      "title":{
        "type": "text"
      },
      "desc":{
        "type":"text"
      }

    }
  }
}
```

## 创建索引正确请求

PUT http://localhost:9200/accounts
Content-Type: application/json

```json
{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties":{
      "user":{
        "type":"text"
      },
      "title":{
        "type": "text"
      },
      "desc":{
        "type":"text"
      }

    }
  }
}
```

## 查看索引映射
```shell
GET http://localhost:9200/accounts/_mappings
```


## 查看索引配置
```shell
GET http://localhost:9200/accounts/_settings
```



## CURD

### 插入数据
```shell
POST http://localhost:9200/accounts/_doc/1
Content-Type: application/json

{
  "user": "张三",
  "title": "工程师",
  "desc": "数据管理员"
}
```

### 检索数据
```shell
GET http://localhost:9200/accounts/_search
Content-Type: application/json

{
  "query": {
    "match": {
       "desc": "数据"
    }
  }
}

```
