package org.apache.lucene.spanquery;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;

/**
 * @see https://blog.csdn.net/z_ssyy/article/details/105234446
 */
//@Slf4j
//@RunWith(SpringRunner.class)
//@SpringBootTest(classes = Application.class)
public class LuceneTests {


    // 创建索引
    @Test
    public void testCreate() throws Exception {
        //1 创建文档对象
        Document document = new Document();
        // 创建并添加字段信息。参数：字段的名称、字段的值、是否存储，这里选Store.YES代表存储到文档列表。Store.NO代表不存储
        document.add(new StringField("id", "1", Field.Store.YES));
        // 这里我们title字段需要用TextField，即创建索引又会被分词。StringField会创建索引，但是不会被分词
        document.add(new TextField("title", "谷歌地图之父跳槽facebook", Field.Store.YES));

        //2 索引目录类,指定索引在硬盘中的位置
        Directory directory = FSDirectory.open(Paths.get("g:\\indexDir"));
        //3 创建分词器对象
        Analyzer analyzer = new StandardAnalyzer();
        //4 索引写出工具的配置对象
        IndexWriterConfig conf = new IndexWriterConfig(analyzer);
        //5 创建索引的写出工具类。参数：索引的目录和配置信息
        IndexWriter indexWriter = new IndexWriter(directory, conf);

        //6 把文档交给IndexWriter
        indexWriter.addDocument(document);
        //7 提交
        indexWriter.commit();
        //8 关闭
        indexWriter.close();
    }

    // 批量创建索引
    @Test
    public void testCreate2() throws Exception {
        // 创建文档的集合
        Collection<Document> docs = new ArrayList<>();
        // 创建文档对象
        Document document1 = new Document();
        document1.add(new StringField("id", "1", Field.Store.YES));
        document1.add(new TextField("title", "谷歌地图之父跳槽facebook", Field.Store.YES));
        docs.add(document1);
        // 创建文档对象
        Document document2 = new Document();
        document2.add(new StringField("id", "2", Field.Store.YES));
        document2.add(new TextField("title", "谷歌地图之父加盟FaceBook", Field.Store.YES));
        docs.add(document2);
        // 创建文档对象
        Document document3 = new Document();
        document3.add(new StringField("id", "3", Field.Store.YES));
        document3.add(new TextField("title", "谷歌地图创始人拉斯离开谷歌加盟Facebook", Field.Store.YES));
        docs.add(document3);
        // 创建文档对象
        Document document4 = new Document();
        document4.add(new StringField("id", "4", Field.Store.YES));
        document4.add(new TextField("title", "谷歌地图之父跳槽Facebook与Wave项目取消有关", Field.Store.YES));
        docs.add(document4);
        // 创建文档对象
        Document document5 = new Document();
        document5.add(new StringField("id", "5", Field.Store.YES));
        document5.add(new TextField("title", "谷歌地图之父拉斯加盟社交网站Facebook", Field.Store.YES));
        docs.add(document5);

        // 索引目录类,指定索引在硬盘中的位置
        Directory directory = FSDirectory.open(Paths.get("g:\\indexDir"));
        // 引入IK分词器
//        Analyzer analyzer = new IKAnalyzer();
        Analyzer analyzer = new StandardAnalyzer();
        // 索引写出工具的配置对象
        IndexWriterConfig conf = new IndexWriterConfig(analyzer);
        // 设置打开方式：OpenMode.APPEND 会在索引库的基础上追加新索引。OpenMode.CREATE会先清空原来数据，再提交新的索引
        conf.setOpenMode(IndexWriterConfig.OpenMode.CREATE);

        // 创建索引的写出工具类。参数：索引的目录和配置信息
        IndexWriter indexWriter = new IndexWriter(directory, conf);
        // 把文档集合交给IndexWriter
        indexWriter.addDocuments(docs);
        // 提交
        indexWriter.commit();
        // 关闭
        indexWriter.close();
    }

    @Test
    public void testSearch() throws Exception {
        // 索引目录对象
        Directory directory = FSDirectory.open(Paths.get("g:\\indexDir"));
        // 索引读取工具
        IndexReader reader = DirectoryReader.open(directory);
        // 索引搜索工具
        IndexSearcher searcher = new IndexSearcher(reader);

        // 创建查询解析器,两个参数：默认要查询的字段的名称，分词器
        QueryParser parser = new QueryParser("title", new StandardAnalyzer());
        // 创建查询对象
        Query query = parser.parse("谷歌");

        // 搜索数据,两个参数：查询条件对象要查询的最大结果条数
        // 返回的结果是 按照匹配度排名得分前N名的文档信息（包含查询到的总条数信息、所有符合条件的文档的编号信息）。
        TopDocs topDocs = searcher.search(query, 10);
        // 获取总条数
        System.out.println("本次搜索共找到" + topDocs.totalHits + "条数据");
        // 获取得分文档对象（ScoreDoc）数组.SocreDoc中包含：文档的编号、文档的得分
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;
        for (ScoreDoc scoreDoc : scoreDocs) {
            // 取出文档编号
            int docID = scoreDoc.doc;
            // 根据编号去找文档
            Document doc = reader.document(docID);
            System.out.println("id: " + doc.get("id"));
            System.out.println("title: " + doc.get("title"));
            // 取出文档得分
            System.out.println("得分： " + scoreDoc.score);
        }
    }

    //特殊查询
    public void search(Query query) throws Exception {
        // 索引目录对象
        Directory directory = FSDirectory.open(Paths.get("g:\\indexDir"));
        // 索引读取工具
        IndexReader reader = DirectoryReader.open(directory);
        // 索引搜索工具
        IndexSearcher searcher = new IndexSearcher(reader);

        // 搜索数据,两个参数：查询条件对象要查询的最大结果条数
        // 返回的结果是 按照匹配度排名得分前N名的文档信息（包含查询到的总条数信息、所有符合条件的文档的编号信息）。
        TopDocs topDocs = searcher.search(query, 10);
        // 获取总条数
        System.out.println("本次搜索共找到" + topDocs.totalHits + "条数据");
        // 获取得分文档对象（ScoreDoc）数组.SocreDoc中包含：文档的编号、文档的得分
        ScoreDoc[] scoreDocs = topDocs.scoreDocs;

        for (ScoreDoc scoreDoc : scoreDocs) {
            // 取出文档编号
            int docID = scoreDoc.doc;
            // 根据编号去找文档
            Document doc = reader.document(docID);
            System.out.println("id: " + doc.get("id"));
            System.out.println("title: " + doc.get("title"));
            // 取出文档得分
            System.out.println("得分： " + scoreDoc.score);
        }
    }

    /*
     * 测试普通词条查询
     * 注意：Term(词条)是搜索的最小单位，不可再分词。值必须是字符串！
     */
    @Test
    public void testTermQuery() throws Exception {
        // 创建词条查询对象
        Query query = new TermQuery(new Term("title", "谷歌地图"));
        search(query);
    }

    /*
     * 测试通配符查询
     * 	? 可以代表任意一个字符
     * 	* 可以任意多个任意字符
     */
    @Test
    public void testWildCardQuery() throws Exception {
        // 创建查询对象
        Query query = new WildcardQuery(new Term("title", "*歌*"));
        search(query);
    }

    /*
     * 测试：数值范围查询
     * 注意：数值范围查询，可以用来对非String类型的ID进行精确的查找
     */
    @Test
    public void testNumericRangeQuery() throws Exception {
        // 数值范围查询对象，参数：字段名称，最小值、最大值、是否包含最小值、是否包含最大值
//        Query query = NumericRangeQuery.newLongRange("id", 2L, 2L, true, true);
//
//        search(query);
    }

    /*
     * 布尔查询：
     * 	布尔查询本身没有查询条件，可以把其它查询通过逻辑运算进行组合！
     * 交集：Occur.MUST + Occur.MUST
     * 并集：Occur.SHOULD + Occur.SHOULD
     * 非：Occur.MUST_NOT
     */
    @Test
    public void testBooleanQuery() throws Exception {

//        Query query1 = NumericRangeQuery.newLongRange("id", 1L, 3L, true, true);
//        Query query2 = NumericRangeQuery.newLongRange("id", 2L, 4L, true, true);
//        // 创建布尔查询的对象
//        BooleanQuery query = new BooleanQuery();
//        // 组合其它查询
//        query.add(query1, BooleanClause.Occur.MUST_NOT);
//        query.add(query2, BooleanClause.Occur.SHOULD);
//
//        search(query);
    }

    /* 测试：修改索引
     * 注意：
     * 	A：Lucene修改功能底层会先删除，再把新的文档添加。
     * 	B：修改功能会根据Term进行匹配，所有匹配到的都会被删除。这样不好
     * 	C：因此，一般我们修改时，都会根据一个唯一不重复字段进行匹配修改。例如ID
     * 	D：但是词条搜索，要求ID必须是字符串。如果不是，这个方法就不能用。
     * 如果ID是数值类型，我们不能直接去修改。可以先手动删除deleteDocuments(数值范围查询锁定ID)，再添加。
     */
    @Test
    public void testUpdate() throws Exception {
        // 创建目录对象
        Directory directory = FSDirectory.open(Paths.get("g:\\indexDir"));
        // 创建配置对象
        IndexWriterConfig conf = new IndexWriterConfig(new StandardAnalyzer());
        // 创建索引写出工具
        IndexWriter writer = new IndexWriter(directory, conf);

        // 创建新的文档数据
        Document doc = new Document();
        doc.add(new StringField("id", "1", Field.Store.YES));
        doc.add(new TextField("title", "谷歌地图之父跳槽facebook ", Field.Store.YES));
        /* 修改索引。参数：
         * 	词条：根据这个词条匹配到的所有文档都会被修改
         * 	文档信息：要修改的新的文档数据
         */
        writer.updateDocument(new Term("id", "1"), doc);
        // 提交
        writer.commit();
        // 关闭
        writer.close();
    }

    /*
     * 演示：删除索引
     * 注意：
     * 	一般，为了进行精确删除，我们会根据唯一字段来删除。比如ID
     * 	如果是用Term删除，要求ID也必须是字符串类型！
     */
    @Test
    public void testDelete() throws Exception {
        // 创建目录对象
        Directory directory = FSDirectory.open(Paths.get("g:\\indexDir"));
        // 创建配置对象
        IndexWriterConfig conf = new IndexWriterConfig(new StandardAnalyzer());
        // 创建索引写出工具
        IndexWriter writer = new IndexWriter(directory, conf);

        // 根据词条进行删除
        //		writer.deleteDocuments(new Term("id", "1"));

        // 根据query对象删除,如果ID是数值类型，那么我们可以用数值范围查询锁定一个具体的ID
        //		Query query = NumericRangeQuery.newLongRange("id", 2L, 2L, true, true);
        //		writer.deleteDocuments(query);

        // 删除所有
        writer.deleteAll();
        // 提交
        writer.commit();
        // 关闭
        writer.close();
    }


}
