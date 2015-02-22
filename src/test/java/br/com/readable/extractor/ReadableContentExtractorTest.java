package br.com.readable.extractor;

import java.io.*;

import org.apache.commons.io.IOUtils;

import org.htmlcleaner.CleanerProperties;
import org.htmlcleaner.DomSerializer;
import org.htmlcleaner.HtmlCleaner;
import org.htmlcleaner.TagNode;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public class ReadableContentExtractorTest {

    private final static CleanerProperties props = new CleanerProperties();

    static {
        props.setRecognizeUnicodeChars(false);
        props.setAdvancedXmlEscape(true);
        props.setAllowMultiWordAttributes(true);
        props.setAllowHtmlInsideAttributes(true);
        props.setIgnoreQuestAndExclam(false);
        props.setOmitUnknownTags(false);
        props.setOmitComments(false);
        props.setNamespacesAware(false);
        props.setTranslateSpecialEntities(false);
        props.setTransSpecialEntitiesToNCR(true);
        props.setTreatUnknownTagsAsContent(false);
        props.setUseEmptyElementTags(false);
        props.setUseCdataForScriptAndStyle(false);
    }

    @Test
    public void testExtract() throws Exception {

        HtmlCleaner cleaner = new HtmlCleaner(props);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try(InputStream in = this.getClass().getResourceAsStream("/germany_military.html")){
            IOUtils.copy(in, out);
        }

        long parse = 0L, extract = 0L;

        byte[] html = out.toByteArray();
        long begin = System.nanoTime();
        TagNode node = cleaner.clean(new ByteArrayInputStream(html), "UTF-8");
        Document document = new DomSerializer(new CleanerProperties(), false).createDOM(node);
        document.setDocumentURI("http://www.bbc.co.uk/portuguese/noticias/2015/02/150203_presa_indonesia_brasil_pai");
        parse += System.nanoTime() - begin;

        ReadableContentExtractor extractor = new ReadableContentExtractor(document);
        begin = System.nanoTime();
        Element main = extractor.extract();
        extract += System.nanoTime() - begin;

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        DOMSource domSource = new DOMSource(main);
        StringWriter writer = new StringWriter();
        StreamResult result = new StreamResult(writer);
        transformer.transform(domSource, result);

        System.out.println("Parse: " + (parse / 1000000) + " Extract: " + (extract / 1000000));
        System.out.println("Html: " + writer.toString());

        /*File htmlFile = new File("test.html");
        FileUtils.writeStringToFile(htmlFile, writer.toString());
        Desktop.getDesktop().browse(htmlFile.toURI());*/
    }
}
