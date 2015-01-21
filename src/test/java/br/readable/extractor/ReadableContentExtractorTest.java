package br.readable.extractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;

import org.apache.commons.io.IOUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.Test;

public class ReadableContentExtractorTest {

    @Test
    public void testExtract() throws Exception {

        Document doc = null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try(InputStream in = this.getClass().getResourceAsStream("/sample4.html")){
            IOUtils.copy(in, out);
        }

        long parse = 0L, extract = 0L;

        byte[] html = out.toByteArray();
        long begin = System.nanoTime();
        doc = Jsoup.parse(new ByteArrayInputStream(html), "UTF-8", "http://wiki.hpc-europa.eu/twiki/bin/search/Main/WebHome");
        parse += System.nanoTime() - begin;

        ReadableContentExtractor extractor = new ReadableContentExtractor(doc);
        begin = System.nanoTime();
        Element main = extractor.extract();
        extract += System.nanoTime() - begin;

        System.out.println("Parse: " + (parse / 1000000) + " Extract: " + (extract / 1000000));
        System.out.println("Html: " + main.html());
    }
}
