package org.apache.lucene.spanquery;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.spans.SpanNearQuery;
import org.apache.lucene.search.spans.SpanNotQuery;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.SpanTermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * @author: chenwm
 * @since: 2023/9/18 10:47
 */
public class SpanNearQueryTest {
    @Test
    public void test() throws IOException {
        WhitespaceAnalyzer analyzer = new WhitespaceAnalyzer();
        TokenStream tokenStream = analyzer.tokenStream("text", "dog dog the quick quick quick quick quick ? brown fox jumps over the lazy dog");
        OffsetAttribute offsetAttribute = tokenStream.addAttribute(OffsetAttribute.class);
        tokenStream.reset();

        List<String> tokens = new ArrayList<>();
        while (tokenStream.incrementToken()) {
            tokens.add(offsetAttribute.toString());
        }
        tokenStream.end();

        System.out.println(String.format("tokens:%s", tokens));
    }

    /**
     * https://developer.aliyun.com/article/45341
     *
     * @throws Exception
     */
    @Test
    public void testCreate() throws Exception {
        //        Directory dir = new RAMDirectory();
        Directory dir = FSDirectory.open(Paths.get("d:\\indexDir"));

        Analyzer analyzer = new WhitespaceAnalyzer();

        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        IndexWriter writer = new IndexWriter(dir, iwc);

        Document doc = new Document();
        doc.add(new TextField("text", "dog dog the quick quick quick quick quick ? brown fox jumps over the lazy dog", Field.Store.YES));
        writer.addDocument(doc);

        doc = new Document();
        doc.add(new TextField("text", "the quick red fox jumps over the sleepy cat", Field.Store.YES));
        writer.addDocument(doc);

        doc = new Document();
        doc.add(new TextField("text", "dog dog the quick quick quick quick quick quick quick quick brown fox jumps over the lazy dog dog dog dog dog dog", Field.Store.YES));
        writer.addDocument(doc);
        writer.commit();
        writer.close();
    }

    @Test
    public void spanNearQueryTest() throws IOException {

        Directory dir = FSDirectory.open(Paths.get("d:\\indexDir"));
        IndexReader reader = DirectoryReader.open(dir);
        IndexSearcher searcher = new IndexSearcher(reader);

        String queryStringStart = "quick";
        String queryStringEnd = "dog";
        SpanQuery queryStart = new SpanTermQuery(new Term("text", queryStringStart));
        SpanQuery queryEnd = new SpanTermQuery(new Term("text", queryStringEnd));
        SpanQuery spanNearQuery = new SpanNearQuery(
                new SpanQuery[]{queryStart, queryEnd}, 7, true);

        SpanQuery exclude = new SpanTermQuery(new Term("text", "?"));
        SpanNotQuery notQuery = new SpanNotQuery(spanNearQuery, exclude);

        TopDocs results = searcher.search(notQuery, 100);
        ScoreDoc[] scoreDocs = results.scoreDocs;

        for (int i = 0; i < scoreDocs.length; ++i) {
            //System.out.println(searcher.explain(query, scoreDocs[i].doc));
            int docID = scoreDocs[i].doc;
            Document document = searcher.doc(docID);
            String path = document.get("text");
            System.out.println("docID:" + docID + " text:" + path);
        }
    }

}
