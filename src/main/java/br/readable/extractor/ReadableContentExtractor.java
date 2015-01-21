package br.readable.extractor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Attributes;
import org.jsoup.nodes.Comment;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.FastNodeUtils;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeVisitor;

/**
 * Created by andre on 8/10/14.
 */
public class ReadableContentExtractor {

    private Document doc;
    private Element mainContent = null;
    private boolean imageBeforeText = false;

    private Matcher contentPattern = Pattern.compile("post|entry|content|text|body|article|story", Pattern.CASE_INSENSITIVE).matcher("");
    private Matcher stopwordPattern = Pattern.compile("comm?ents?|komm?ents?|share|footer|^ad|footnote|breadcrumb|menu|pub", Pattern.CASE_INSENSITIVE).matcher("");
    private Matcher allowedAttrPattern = Pattern.compile("src|data-src|href|text", Pattern.CASE_INSENSITIVE).matcher("");
    private Matcher sphereitPattern = Pattern.compile("sphereit", Pattern.CASE_INSENSITIVE).matcher("");
    private Matcher imageSizePattern = Pattern.compile("(\\d+).*").matcher("");

    public ReadableContentExtractor() {
    }

    public ReadableContentExtractor(final Document doc) {
        this.doc = doc;
    }

    public void reset(Document doc){
        this.doc = doc;
        imageBeforeText = false;
        mainContent = null;
    }

    public Element extract() {
        // Create result Document
        Element articleContent = this.doc.createElement("div");
        Element articleTitle = this.doc.createElement("h1");

        // Grabbing article title
        String title = getTitle();
        articleTitle.attr("class", "title");
        articleTitle.html(title);

        // Grabbing main description
        String description = getDescription();

        // Grabbing main image
        String imageURL = getMainImage();

        // Grabbing main content (need to be fetched at this point)
        mainContent = getMainContent(title, description);

        articleContent.appendChild(articleTitle);
        if (description != null) {
            Element articleDescription = this.doc.createElement("p");
            articleDescription.attr("class", "intro");
            articleDescription.text(description);
            articleContent.appendChild(articleDescription);
        }

        if (imageURL != null && mainContent.select("figure").isEmpty() && !imageBeforeText) {
            Element articleImageWrapper = this.doc.createElement("div");
            articleImageWrapper.attr("id", "mainImage");
            Element articleImage = this.doc.createElement("img");
            articleImage.attr("src", imageURL);
            articleImageWrapper.appendChild(articleImage);
            articleContent.appendChild(articleImageWrapper);
        }

        articleContent.appendChild(mainContent);
        return articleContent;
    }

    public String getTitle() {
        // Check on meta OG information at first
        String title = null;
        List<Element> metaElements = this.doc.getElementsByTag("meta");
        for (Element meta : metaElements) {
            if (meta.attr("property").equals("og:title"))
                title = meta.attr("content");
        }

        if (title == null || title.isEmpty())
            title = this.doc.title();

        return title;
    }

    public Element getMainContent() {
        return getMainContent(null, null);
    }

    public Element getMainContent(String title) {
        return getMainContent(title, null);
    }

    public Element getMainContent(String title, String description) {
        // Scoring elements
        Element body = this.doc.body();
        Element topDiv = null;

        if (body != null) {
            killBrWrapPattern(body);

            // Selecting all paragraphs
            Set<Node> allParagraphs = new HashSet<>();
            allParagraphs.addAll(body.getElementsByTag("p"));

            // Selecting text enclosed by div's and not p's
            Elements elements = body.getElementsByTag("div");
            for (Element element : elements){
                for (TextNode node : element.textNodes()) {
                    if (!node.isBlank()) {
                        allParagraphs.add(node);
                        break;
                    }
                }
            }

            // Score elements
            Map<Element, Integer> readabilityScoreMap = scoreElements(allParagraphs);

            // Detecting div with higher score
            for (Map.Entry<Element, Integer> nodeEntry : readabilityScoreMap.entrySet()) {
                if (topDiv == null ||
                        (readabilityScoreMap.get(nodeEntry.getKey()) > readabilityScoreMap.get(topDiv)))
                    topDiv = nodeEntry.getKey();
            }
        }

        if (topDiv == null) {
            topDiv = this.doc.createElement("p");
            topDiv.text("Could not extract readable content from this page.");
            return topDiv;
        }

        // Cleans out junk from the topDiv just in case:
        clean(topDiv, "form");
        clean(topDiv, "nav");
        clean(topDiv, "table", 8);
        clean(topDiv, "iframe");
        clean(topDiv, "font");
        clean(topDiv, "script");
        clean(topDiv, "aside");
        clean(topDiv, "button");
        clean(topDiv, "hr");

        // Goes in and removes DIV's that have more non <p> stuff than <p> stuff
        killDivs(topDiv);

        // removes span tags
        // Removes any consecutive <br />'s into just one <br />
        killCodeSpansAndBreaks(topDiv);

        // Clean style and scripts
        cleanIrrelevantImages(topDiv);
        cleanIrrelevantAttributes(topDiv);

        // Remove title from the text (duplicates may exists inside article div)
        if (title != null) {
            List<Element> duplicateTitleCandidates = topDiv.select("h1, h2, h3");
            for (Element duplicateCandidate : duplicateTitleCandidates) {
                if (title.equals(duplicateCandidate.ownText()))
                    duplicateCandidate.remove();
            }
        }

        // Remove description from the text (duplicates may exists inside article div)
        if (description != null) {
            Element duplicateCandidate = null;
            Elements duplicateCandidates = topDiv.select("p");
            for (Element el : duplicateCandidates) {
                if (!hasVisibleChar(el, true)) {
                    duplicateCandidate = el;
                    break;
                }
            }
            if (duplicateCandidate != null && description.equals(duplicateCandidate.ownText()))
                duplicateCandidate.remove();
        }

        return topDiv;
    }

    public String getDescription() {
        // Check on meta OG information as fallback solution
        List<Element> metaElements = this.doc.getElementsByTag("meta");
        for (Element element : metaElements) {
            if (element.attr("property").equals("og:description"))
                return element.attr("content");
            if (element.attr("name").equals("description"))
                return element.attr("content");
        }

        return null;
    }

    public Set<String> getMainImages(Integer number) {

        if (number != null && number < 1)
            throw new IllegalArgumentException("Number of images need to be bigger or equal to 1.");

        Set<String> result = new LinkedHashSet<String>();
        if(mainContent == null) mainContent = this.getMainContent();

        for(Element image: mainContent.getElementsByTag("img")){
            if(image.hasAttr("src")){
                result.add(image.attr("abs:src"));
                if(number != null && result.size() == number) break;
            }
        }

        return result;
    }

    protected String getMainImage() {
        // Check on meta OG information at first
        String imageURL = null;
        List<Element> metaElements = this.doc.getElementsByTag("meta");
        for (Element element : metaElements) {
            if (element.attr("property").equals("og:image"))
                imageURL = element.attr("content");
        }

        // If first element is also an image, drop the image from the OG
        if (imageURL != null && imageBeforeText)
            return null;

        return imageURL;
    }

    protected Map<Element, Integer> scoreElements(Collection<Node> nodes) {
        Map<Element, Integer> readabilityScoreMap = new HashMap<>();

        // Fetching element readability score
        for (Node node : nodes) {
            Node parent = node.parent();
            if (!(parent instanceof Element))
                continue;

            Element parentEl = (Element) parent;
            if (!parentEl.tagName().equalsIgnoreCase("div") && !parentEl.tagName().equalsIgnoreCase("article"))
                continue;

            Integer score = readabilityScoreMap.get(parentEl);
            if (score == null)
                score = scoreByMatchClassId(parentEl);

            if (textLength(node) > 30)
                score += Math.max(30, textLength(node) / 5);

            if(node instanceof Element) score += (StringUtils.countMatches(((Element)node).text(), ","));
            if(node instanceof TextNode) score += (StringUtils.countMatches(((TextNode)node).text(), ","));
            if (score >= 0) readabilityScoreMap.put(parentEl, score);
        }

        // Consider also the grandparent level
        for (Node node : nodes) {
            Node parent = node.parent();
            Node grandPa = node.parent().parent();
            if (grandPa == null || !(parent instanceof Element) || !(grandPa instanceof Element))
                continue;

            Element parentEl = (Element) parent;
            Element grandPaEl = (Element) grandPa;
            if (!parentEl.tagName().equals("div") || (!grandPaEl.tagName().equals("div")))
                continue;

            Integer score = readabilityScoreMap.get(grandPaEl);
            if (score == null)
                score = scoreByMatchClassId(grandPaEl);

            if (textLength(node) > 30)
                score += Math.max(20, textLength(node) / 10);

            if(node instanceof Element) score += (StringUtils.countMatches(((Element)node).text(), ",") / 2);
            if(node instanceof TextNode) score += (StringUtils.countMatches(((TextNode)node).text(), ",") / 2);
            if (score >= 0) readabilityScoreMap.put(grandPaEl, score);
        }

        return readabilityScoreMap;
    }
    
    protected Integer scoreByMatchClassId(Element e) {
        Integer result = 0;
        if (contentPattern.reset(e.classNames().toString()).find())
            result += 100;
        else if (stopwordPattern.reset(e.classNames().toString()).find())
            result -= 999;

        if (contentPattern.reset(e.id()).find())
            result += 100;
        else if (stopwordPattern.reset(e.id()).find())
            result -= 999;

        String itemprop = e.attr("itemprop");
        if (itemprop != null && !itemprop.isEmpty()) {
            if (contentPattern.reset(itemprop).find())
                result += 100;
            else if (stopwordPattern.reset(itemprop).find())
                result -= 999;
        }

        return result;
    }

    protected void killBrWrapPattern(Element e) {

        List<Node> children = new ArrayList<Node>(e.childNodes());

        boolean hasBr = false;
        for (Node child: children){
            if(child instanceof Element){
                Element element = (Element) child;
                if("br".equals(element.tagName())) hasBr = true;
                else
                    killBrWrapPattern(element);
            }
        }

        if(!hasBr) return;

        FastNodeUtils.removeChildren(e);
        Element p = null;

        for(Node child: children){
            if(child instanceof Element && "br".equals(((Element)child).tagName())){
                p = null;
                continue;
            }

            if(p == null) p = e.appendElement("p");
            p.appendChild(child);
        }
    }

    protected boolean killCodeSpansAndBreaks(Element e) {

        List<Node> children = new ArrayList<Node>(e.childNodes());
        TextAppender appender = new TextAppender();

        boolean hasChild = false;

        FastNodeUtils.removeChildren(e);

        for(Node child: children){

            if(child instanceof Element){

                Element element = (Element) child;
                String name = element.tagName();

                if("br".equals(name) || "span".equals(name)){
                    continue;
                };

                boolean hasGChild = killCodeSpansAndBreaks(element);

                if(("p".equals(name) || "div".equals(name)) && !hasGChild){
                    appender.append(" ");
                    continue;
                }

                e.appendText(appender.toString());
                appender.reset();
                e.appendChild(element);
                hasChild = true;

            }else if(child instanceof TextNode){
                appender.append(((TextNode)child).getWholeText());
            }
        }

        hasChild |= appender.hasVisibleChar;
        String text = appender.toString();
        if(text.length() > 0) e.appendText(text);

        return hasChild;
    }

    protected void killDivs (Element e) {
        Elements divsList = e.getElementsByTag( "div" );

        // Gather counts for other typical elements embedded within.
        // Traverse backwards so we can remove nodes at the same time without effecting the traversal.
        for (Element div : divsList) {
            int pCount = div.getElementsByTag("p").size();
            int imgCount = div.getElementsByTag("img").size();
            int liCount = div.getElementsByTag("li").size();
            int aCount = div.getElementsByTag("a").size();
            int embedCount = div.getElementsByTag("embed").size();
            int objectCount = div.getElementsByTag("object").size();
            int preCount = div.getElementsByTag("pre").size();
            int codeCount = div.getElementsByTag("code").size();

            int sphereit = findComment(div, sphereitPattern) ? 0 : 1;

            // If the number of commas is less than 10 (bad sign) ...

            if (textLength(div) < 10 ) {
                // And the number of non-paragraph elements is more than paragraphs or other ominous signs :
                if (( imgCount > pCount || liCount > pCount || aCount > pCount || pCount == 0)
                        && ( preCount == 0 && codeCount == 0 && embedCount == 0 && objectCount == 0 && sphereit == 0 )) {
                    if (pCount != 0 && imgCount == 1)
                        div.remove();
                }
            }

            String divId = div.id();
            String divClasses = StringUtils.join(div.classNames(), " ");

            // Removing elements by stopwords
            if (stopwordPattern.reset(divId).find() || stopwordPattern.reset(divClasses).find())
                div.remove();
        }
    }

    protected void cleanIrrelevantAttributes(Element root) {

        root.traverse(new NodeVisitor() {

            boolean foundParagraph = false;

            @Override
            public void head(Node node, int i) {
                if (node instanceof  Element) {
                    Element el = ((Element) node);
                    String tagName = el.tagName();
                    if (tagName.equals("p") && (el.ownText().length() > 20))
                        foundParagraph = true;
                    else if (tagName.equals("img") && !foundParagraph)
                        imageBeforeText = true;
                }
            }

            @Override
            public void tail(Node node, int i) {
                Attributes attributes = node.attributes();
                for (Attribute attribute : attributes) {
                    String key = attribute.getKey();
                    String value = attribute.getValue();
                    if (!allowedAttrPattern.reset(key).find() ||
                            (key.matches("(?i:href)") && value.startsWith("javascript:")))
                        node.attributes().remove(key);
                }
            }
        });
    }

    protected void cleanIrrelevantImages(Element root) {
        // Removing irrelevant images
        List<Element> images = root.getElementsByTag("img");
        for (Element image : images) {
            String heightStr = image.attr("height");
            String widthStr = image.attr("width");
            Integer width = null;
            Integer height = null;
            if (widthStr != null && !widthStr.isEmpty()) {
                Matcher matcher = imageSizePattern.reset(widthStr);
                if (matcher.matches())
                    width = Integer.parseInt(matcher.group(1));
            } if (heightStr != null && !heightStr.isEmpty()) {
                Matcher matcher = imageSizePattern.reset(heightStr);
                if (matcher.matches())
                    height = Integer.parseInt(matcher.group(1));
            }

            if ((width != null && width < 70) || (height != null && height < 70)) {
                image.remove();
                continue;
            }

            // Remove "data-src" attr, if exists
            String copy = image.attr("data-src");
            if (copy != null && !copy.isEmpty()) {
                image.attr("src", copy);
                image.removeAttr("data-src");
            }

            // Set absolute path for all images
            String absolute = image.absUrl("src");
            image.attr("src", absolute);
        }
    }

    protected void clean(Element e, String tagName) {
        this.clean(e, tagName, 1000000);
    }

    protected void clean(Element e, String tagName, Integer minWords) {
        Elements targetList;

        if (tagName.equalsIgnoreCase("table")) {
            targetList = e.getElementsByTag(tagName);
            for (Element target : targetList) {
                // If the text content isn't laden with words, remove the child:
                int cells = target.getElementsByTag("td").size();
                if (cells < minWords)
                    target.remove();
            }
        } else {
            targetList = e.getElementsByTag(tagName);

            for (Element target : targetList) {
                int length = textLength(target);
                if ((length < minWords) && !target.tagName().equalsIgnoreCase("pre"))
                    target.remove();
            }
        }
    }

    protected boolean hasVisibleChar(Node node, boolean checkOnlyChildren){

        for(Node child: node.childNodes()) {
            if (child instanceof TextNode) {
                String text = ((TextNode)child).getWholeText();
                for(int i = 0; i < text.length(); i++){
                    char c = text.charAt(i);
                    if(!Character.isWhitespace(c) && c != 160) return true;
                }
            }else if(child instanceof Element && !checkOnlyChildren){
                    if(hasVisibleChar(child, true)) return true;
            }
        }
        return false;
    }

    protected int textLength(Node node){
        int length = 0;
        for(Node child: node.childNodes()){
            if(child instanceof Element) length += textLength(child);
            if(child instanceof TextNode) length += ((TextNode)child).getWholeText().length();
        }
        return length;
    }

    protected int countMatches(Node node, CharSequence pat){

        int count = 0;
        for(Node child: node.childNodes()){
            if(child instanceof Element) count += countMatches(child, pat);
            if(child instanceof TextNode){
                count += StringUtils.countMatches(((TextNode)child).getWholeText(), pat);
            }
        }
        return count;
    }

    protected boolean findComment(Node node, Matcher matcher){

        for(Node cnode: node.childNodes()){
            boolean match = false;
            if(cnode instanceof Element) match = findComment(cnode, matcher);
            if(cnode instanceof Comment){
                match = matcher.reset(((Comment)cnode).getData()).find();
            }
            if(match) return  true;
        }
        return false;
    }

    private static class TextAppender {

        private final StringBuilder buf = new StringBuilder();

        private boolean lastIsWhiteSpace = false;
        private boolean hasVisibleChar = false;

        public void reset() {
            lastIsWhiteSpace = false;
            hasVisibleChar = false;
            buf.setLength(0);
        }

        public boolean hasVisibleChar() {
            return hasVisibleChar;
        }

        @Override
        public String toString() {
            if(lastIsWhiteSpace) buf.append(' ');
            return buf.toString();
        }

        public int length() {
            return buf.length();
        }

        public void append(String str) {

            int length = str.length();

            for (int i = 0; i < length; i++) {

                char c = str.charAt(i);
                if (Character.isWhitespace(c) || c == 160){
                    lastIsWhiteSpace = true;
                    continue;
                }

                if (lastIsWhiteSpace) {
                    buf.append(' ');
                    lastIsWhiteSpace = false;
                }

                int j;
                for (j = i + 1; j < length; j++) {
                    c = str.charAt(j);
                    if (Character.isWhitespace(c)  || c == 160) {
                        lastIsWhiteSpace = true;
                        break;
                    }
                }

                buf.append(str, i, j);
                hasVisibleChar = true;
                i = j;
            }
        }
    }
}
