package br.readable.extractor;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import org.w3c.dom.*;
import org.w3c.dom.traversal.DocumentTraversal;
import org.w3c.dom.traversal.NodeFilter;
import org.w3c.dom.traversal.TreeWalker;

public class ReadableContentExtractor {

    private Document doc;
    private Element mainContent = null;
    private boolean imageBeforeText = false;

    private Matcher contentPattern = Pattern.compile("post|entry|content|text|body|article|story", Pattern.CASE_INSENSITIVE).matcher("");
    private Matcher stopwordPattern = Pattern.compile("comm?ents?|komm?ents?|share|footer|^ad|footnote|skip|breadcrumb|menu|continue|pub", Pattern.CASE_INSENSITIVE).matcher("");
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
        articleTitle.setAttribute("class", "title");
        articleTitle.setTextContent(title);

        // Grabbing main description
        String description = getDescription();

        // Grabbing main image
        String imageURL = getMainImage();

        // Grabbing main content (need to be fetched at this point)
        mainContent = getMainContent(title, description);

        articleContent.appendChild(articleTitle);
        if (description != null) {
            Element articleDescription = this.doc.createElement("p");
            articleDescription.setAttribute("class", "intro");
            articleDescription.setTextContent(description);
            articleContent.appendChild(articleDescription);
        }

        if (imageURL != null && this.mainContent.getElementsByTagName("figure").getLength() == 0 && !imageBeforeText) {
            Element articleImageWrapper = this.doc.createElement("div");
            articleImageWrapper.setAttribute("id", "mainImage");
            Element articleImage = this.doc.createElement("img");
            articleImage.setAttribute("src", imageURL);
            articleImageWrapper.appendChild(articleImage);
            articleContent.appendChild(articleImageWrapper);
        }

        articleContent.appendChild(mainContent);
        return articleContent;
    }

    public String getTitle() {
        // Check on meta OG information at first
        String title = null;
        NodeList metaElements = this.doc.getElementsByTagName("meta");
        for (int i = 0; i < metaElements.getLength(); i++) {
            Element meta = (Element) metaElements.item(i);
            if (meta.getAttribute("property").equals("og:title"))
                title = meta.getAttribute("content");
        }

        if (title == null || title.isEmpty()) {
            NodeList titleEls = this.doc.getElementsByTagName("title");
            if (titleEls.getLength() > 0)
                title = titleEls.item(0).getNodeValue();
        }

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
        NodeList nodelist = this.doc.getElementsByTagName("body");
        if (nodelist.getLength() == 0)
            return null;

        Element body = (Element) nodelist.item(0);
        Element topDiv = null;

        if (body != null) {
            killBrWrapPattern(body);

            // Selecting all paragraphs
            Set<Node> allParagraphs = new HashSet<>();
            NodeList paragraphsList = body.getElementsByTagName("p");
            for (int i = 0; i < paragraphsList.getLength(); i++) {
                allParagraphs.add(paragraphsList.item(i));
            }

            // Selecting text enclosed by div's and not p's
            NodeList metaElements = body.getElementsByTagName("div");
            for (int i = 0; i < metaElements.getLength(); i++) {
                Element element = (Element) metaElements.item(i);
                for (Text node : findTextNodes(element)) {
                    if (!node.isElementContentWhitespace() && !node.getTextContent().trim().isEmpty()) {
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
            topDiv.setTextContent("Could not extract readable content from this page.");
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

        topDiv.normalize();

        // Remove title from the text (duplicates may exists inside article div)
        if (title != null) {
            String[] hlist = {"h1", "h2", "h3"};
            for (String hx : hlist) {
                NodeList duplicateCandidates = topDiv.getElementsByTagName(hx);
                for (int i = 0; i < duplicateCandidates.getLength(); i++) {
                    Element duplicateCandidate = (Element) duplicateCandidates.item(i);
                    if (title.equals(getFirstLevelTextContent(duplicateCandidate)))
                        duplicateCandidate.getParentNode().removeChild(duplicateCandidate);
                }
            }
        }

        // Remove description from the text (duplicates may exists inside article div)
        if (description != null) {
            Element duplicateCandidate = null;
            NodeList duplicateCandidates = topDiv.getElementsByTagName("p");
            for (int i = 0; i < duplicateCandidates.getLength(); i++) {
                Element el = (Element) duplicateCandidates.item(i);
                if (!hasVisibleChar(el, true)) {
                    duplicateCandidate = el;
                    break;
                }
            }
            if (duplicateCandidate != null && description.equals(getFirstLevelTextContent(duplicateCandidate)))
                duplicateCandidate.getParentNode().removeChild(duplicateCandidate);
        }

        return topDiv;
    }

    public String getDescription() {
        // Check on meta OG information as fallback solution
        NodeList metaElements = this.doc.getElementsByTagName("meta");
        for (int i = 0; i < metaElements.getLength(); i++) {
            Element element = (Element) metaElements.item(i);
            if (element.getAttribute("property").equals("og:description"))
                return element.getAttribute("content");
            if (element.getAttribute("name").equals("description"))
                return element.getAttribute("content");
        }

        return null;
    }

    public Set<String> getMainImages(Integer number) {

        // Create base URI
        URI base = URI.create(this.doc.getBaseURI());

        if (number != null && number < 1)
            throw new IllegalArgumentException("Number of images need to be bigger or equal to 1.");

        Set<String> result = new LinkedHashSet<>();
        if(mainContent == null) mainContent = this.getMainContent();

        NodeList metaElements = mainContent.getElementsByTagName("img");
        for (int i = 0; i < metaElements.getLength(); i++) {
            Element element = (Element) metaElements.item(i);
            if(element.getAttribute("src") != null){
                result.add(base.resolve(element.getAttribute("src")).toString());
                if(number != null && result.size() == number) break;
            }
        }

        return result;
    }

    protected String getMainImage() {
        // Check on meta OG information at first
        String imageURL = null;
        NodeList metaElements = this.doc.getElementsByTagName("meta");
        for (int i = 0; i < metaElements.getLength(); i++) {
            Element element = (Element) metaElements.item(i);
            if (element.getAttribute("property").equals("og:image"))
                imageURL = element.getAttribute("content");
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
            Node parent = node.getParentNode();
            if (!(parent instanceof Element))
                continue;

            Element parentEl = (Element) parent;
            if (!parentEl.getTagName().equalsIgnoreCase("div") && !parentEl.getTagName().equalsIgnoreCase("article"))
                continue;

            Integer score = readabilityScoreMap.get(parentEl);
            if (score == null)
                score = scoreByMatchClassId(parentEl);

            if (textLength(node) > 30)
                score += Math.max(30, textLength(node) / 5);

            score += (StringUtils.countMatches(node.getTextContent(), ","));
            if (score >= 0) readabilityScoreMap.put(parentEl, score);
        }

        // Consider also the grandparent level
        for (Node node : nodes) {
            Node parent = node.getParentNode();
            Node grandPa = parent.getParentNode();
            if (grandPa == null || !(parent instanceof Element) || !(grandPa instanceof Element))
                continue;

            Element parentEl = (Element) parent;
            Element grandPaEl = (Element) grandPa;
            if (!parentEl.getTagName().equals("div") || (!grandPaEl.getTagName().equals("div")))
                continue;

            Integer score = readabilityScoreMap.get(grandPaEl);
            if (score == null)
                score = scoreByMatchClassId(grandPaEl);

            if (textLength(node) > 30)
                score += Math.max(20, textLength(node) / 10);

            score += (StringUtils.countMatches(node.getTextContent(), ",") / 2);
            if (score >= 0) readabilityScoreMap.put(grandPaEl, score);
        }

        return readabilityScoreMap;
    }
    
    protected Integer scoreByMatchClassId(Element e) {
        Integer result = 0;
        if (contentPattern.reset(e.getAttribute("class")).find())
            result += 100;
        else if (stopwordPattern.reset(e.getAttribute("class")).find())
            result -= 999;

        if (contentPattern.reset(e.getAttribute("id")).find())
            result += 100;
        else if (stopwordPattern.reset(e.getAttribute("id")).find())
            result -= 999;

        String itemprop = e.getAttribute("itemprop");
        if (itemprop != null && !itemprop.isEmpty()) {
            if (contentPattern.reset(itemprop).find())
                result += 100;
            else if (stopwordPattern.reset(itemprop).find())
                result -= 999;
        }

        return result;
    }

    protected void killBrWrapPattern(Element e) {

        List<Node> children = new ArrayList<>();
        NodeList childList = e.getChildNodes();
        for (int i = 0; i < childList.getLength(); i++) {
            children.add(childList.item(i));
        }

        boolean hasBr = false;
        for (Node child: children){
            if(child instanceof Element){
                Element element = (Element) child;
                if("br".equals(element.getTagName())) hasBr = true;
                else
                    killBrWrapPattern(element);
            }
        }

        if(!hasBr) return;

        removeChildren(e);
        Element p = null;

        for(Node child: children){
            if(child instanceof Element && "br".equals(((Element)child).getTagName())){
                p = null;
                continue;
            }

            if(p == null) p = (Element) e.appendChild(this.doc.createElement("p"));
            p.appendChild(child);
        }
    }

    protected boolean killCodeSpansAndBreaks(Element e) {

        List<Node> children = new ArrayList<>();
        NodeList childList = e.getChildNodes();
        for (int i = 0; i < childList.getLength(); i++) {
            children.add(childList.item(i));
        }

        TextAppender appender = new TextAppender();

        boolean hasChild = false;

        removeChildren(e);

        for(Node child: children){

            if(child instanceof Element){

                Element element = (Element) child;
                String name = element.getTagName();

                if("br".equals(name) || "span".equals(name))
                    continue;

                boolean hasGChild = killCodeSpansAndBreaks(element);

                if(("p".equals(name) || "div".equals(name)) && !hasGChild){
                    appender.append(" ");
                    continue;
                }

                e.appendChild(this.doc.createTextNode(appender.toString()));
                appender.reset();
                e.appendChild(element);
                hasChild = child.hasChildNodes();

            }else if(child instanceof Text){
                appender.append(((Text)child).getWholeText());
            }
        }

        hasChild |= appender.hasVisibleChar;
        String text = appender.toString();
        if(text.length() > 0) e.appendChild(this.doc.createTextNode(appender.toString()));

        return hasChild;
    }

    protected void killDivs (Element e) {
        NodeList divsList = e.getElementsByTagName("div");

        // Gather counts for other typical elements embedded within.
        // Traverse backwards so we can remove nodes at the same time without effecting the traversal.
        for (int i = 0; i < divsList.getLength(); i++) {
            Element div = (Element) divsList.item(i);
            int pCount = div.getElementsByTagName("p").getLength();
            int imgCount = div.getElementsByTagName("img").getLength();
            int liCount = div.getElementsByTagName("li").getLength();
            int aCount = div.getElementsByTagName("a").getLength();
            int embedCount = div.getElementsByTagName("embed").getLength();
            int objectCount = div.getElementsByTagName("object").getLength();
            int preCount = div.getElementsByTagName("pre").getLength();
            int codeCount = div.getElementsByTagName("code").getLength();

            int sphereit = findComment(div, sphereitPattern) ? 0 : 1;

            // If the number of commas is less than 10 (bad sign) ...

            if (textLength(div) < 10 ) {
                // And the number of non-paragraph elements is more than paragraphs or other ominous signs :
                if (( imgCount > pCount || liCount > pCount || aCount > pCount || pCount == 0)
                        && ( preCount == 0 && codeCount == 0 && embedCount == 0 && objectCount == 0 && sphereit == 0 )) {
                    if (pCount != 0 && imgCount == 1)
                        div.getParentNode().removeChild(div);
                }
            }

            String divId = div.getAttribute("id");
            String divClasses = StringUtils.join(div.getAttribute("id"), " ");

            // Removing elements by stopwords
            if (stopwordPattern.reset(divId).find() || stopwordPattern.reset(divClasses).find())
                div.getParentNode().removeChild(div);
        }
    }

    protected void cleanIrrelevantAttributes(Element root) {

        DocumentTraversal traversal = (DocumentTraversal) doc;

        TreeWalker walker = traversal.createTreeWalker(root,
                NodeFilter.SHOW_ELEMENT,
                null,
                false);

        boolean foundParagraph = false;
        Node node = walker.nextNode();
        while (node != null) {
            Element el = ((Element) node);
            String tagName = el.getTagName();
            if (tagName.equals("p") && (getFirstLevelTextContent(el).length() > 20))
                foundParagraph = true;
            else if (tagName.equals("img") && !foundParagraph)
                imageBeforeText = true;


            NamedNodeMap attributes = el.getAttributes();
            for (int j = 0; j < attributes.getLength(); j++) {
                Attr attribute = (Attr) attributes.item(j);
                if (!allowedAttrPattern.reset(attribute.getName()).find() ||
                        (attribute.getName().matches("(?i:href)") && attribute.getValue().startsWith("javascript:")))
                    node.getAttributes().removeNamedItem(attribute.getName());
            }

            node = walker.nextNode();
        }
    }

    protected void cleanIrrelevantImages(Element root) {
        // Create base URI
        URI base = URI.create(this.doc.getBaseURI());

        // Removing irrelevant images
        NodeList images = root.getElementsByTagName("img");
        for (int i = 0; i < images.getLength(); i++) {
            Element image = (Element) images.item(i);
            String heightStr = image.getAttribute("height");
            String widthStr = image.getAttribute("width");
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
                image.getParentNode().removeChild(image);
                continue;
            }

            // Remove "data-src" attr, if exists
            String copy = image.getAttribute("data-src");
            if (copy != null && !copy.isEmpty()) {
                image.setAttribute("src", copy);
                image.removeAttribute("data-src");
            }

            // Set absolute path for all images
            String absolute = image.getAttribute("src");
            image.setAttribute("src", base.resolve(absolute).toString());
        }
    }

    protected void clean(Element e, String tagName) {
        this.clean(e, tagName, 1000000);
    }

    protected void clean(Element e, String tagName, Integer minWords) {
        NodeList targetList;

        if (tagName.equalsIgnoreCase("table")) {
            targetList = e.getElementsByTagName(tagName);
            for (int i = 0; i < targetList.getLength(); i++) {
                // If the content isn't laden with words, remove the child:
                Element target = (Element) targetList.item(i);
                int cells = target.getElementsByTagName("td").getLength();
                if (cells < minWords)
                    target.getParentNode().removeChild(target);
            }
        } else {
            targetList = e.getElementsByTagName(tagName);

            for (int i = 0; i < targetList.getLength(); i++) {
                Element target = (Element) targetList.item(i);
                int length = textLength(target);
                if ((length < minWords) && !target.getTagName().equalsIgnoreCase("pre"))
                    target.getParentNode().removeChild(target);
            }
        }
    }

    protected boolean hasVisibleChar(Node node, boolean checkOnlyChildren){

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Text) {
                String text = ((Text)child).getWholeText();
                for(int j = 0; j < text.length(); j++){
                    char c = text.charAt(j);
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
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if(child instanceof Element || child instanceof Text) length += textLength(child);
        }
        return length;
    }

    public static String getFirstLevelTextContent(Node node) {
        NodeList list = node.getChildNodes();
        StringBuilder textContent = new StringBuilder();
        for (int i = 0; i < list.getLength(); ++i) {
            Node child = list.item(i);
            if (child.getNodeType() == Node.TEXT_NODE)
                textContent.append(child.getTextContent());
        }
        return textContent.toString();
    }

    protected int countMatches(Node node, CharSequence pat){

        int count = 0;
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if(child instanceof Element) count += countMatches(child, pat);
            if(child instanceof Text) count += StringUtils.countMatches(((Text)child).getWholeText(), pat);
        }
        return count;
    }

    protected boolean findComment(Node node, Matcher matcher){

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node cnode = children.item(i);
            boolean match = false;
            if(cnode instanceof Element) match = findComment(cnode, matcher);
            if(cnode instanceof Comment) match = matcher.reset(((Comment)cnode).getData()).find();
            if(match) return  true;
        }
        return false;
    }

    protected List<Text> findTextNodes(Node node){
        List<Text> nodes = new ArrayList<>();
        findTextNodesHelper(node, nodes);

        return nodes;
    }

    protected void findTextNodesHelper(Node node, List<Text> nodes){

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node cnode = children.item(i);
            if(cnode instanceof Element) findTextNodesHelper(cnode, nodes);
            if(cnode instanceof Text) nodes.add((Text)cnode);
        }
    }

    protected static void removeChildren(Node node){
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node cnode = children.item(i);
            node.removeChild(cnode);
        }
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
