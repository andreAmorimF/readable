package org.jsoup.nodes;

public class FastNodeUtils {

    public static void removeChildren(Node node){
        for(Node child: node.childNodes){
            child.parentNode = null;
        }
        node.childNodes.clear();
    }
}
