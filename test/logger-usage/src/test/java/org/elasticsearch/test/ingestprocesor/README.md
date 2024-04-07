
# Ingest Pipeline使用

案例链接：https://xie.infoq.cn/article/19f983f16a6796c5af08fbf21
官方链接：https://www.elastic.co/guide/en/elasticsearch/reference/7.6/set-processor.html
知识扩展链接：https://www.cuiliangblog.cn/detail/section/76304999
## 1. 创建Ingest Pipeline

`
PUT http://localhost:9200/_ingest/pipeline/my-pipeline
Content-Type: application/json

{
"description": "My first Ingest Pipeline",
"processors": [
{
"set": {
"field": "location",
"value": "China"
}
},
{
"lowercase": {
"field": "name"
}
}
]
}
`

## 2. 使用 Ingest Pipeline
`
PUT http://localhost:9200/my-index/_doc/1?pipeline=my-pipeline
Content-Type: application/json

{
"name": "Tom",
"age": 18
}
`

## 3. 验证
查看 id 为 1 的文档
GET http://localhost:9200/my-index/_doc/1

# 返回结果
{
"_index": "my-index",
"_type": "_doc",
"_id": "1",
"_version": 1,
"_seq_no": 0,
"_primary_term": 1,
"found": true,
"_source": {
"name": "tom",
"location": "China",
"age": 18
}
}

## 4.使用 Simulate API 测试 Pipeline，好处无须创建索引 + 插入文档，验证Pipeline
`
POST http://localhost:9200/_ingest/pipeline/my-pipeline/_simulate
Content-Type: application/json

{
"docs": [
{
"_source": {
"name": "Tom",
"age": 18
}
}
]
}
`

## 5.无须预先创建 Pipeline + 无须创建索引 + 插入文档，直接验证Pipeline
`
POST http://localhost:9200/_ingest/pipeline/_simulate
Content-Type: application/json

{
"pipeline": {
"processors": [
{
"set": {
"field": "location",
"value": "China"
}
},
{
"lowercase": {
"field": "name"
}
}
]
},
"docs": [
{
"_source": {
"name": "Tom",
"age": 18
}
}
]
}
`
