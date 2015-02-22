Readable
========

Yet another readability library for Java. It works great for news and blog sites.

Check out it working on: http://brreadable.herokuapp.com/

## Installation

### Maven

Maven artifacts can be fetched using this tag on your pom's dependencies section:

```xml
<dependency>
    <groupId>br.com</groupId>
        <artifactId>readable</artifactId>
        <version>1.0</version>
    <type>jar</type>
</dependency>
```

The following repository needs to be specified:

```xml
<repositories>
    <repository>
        <snapshots>
            <enabled>false</enabled>
        </snapshots>
        <id>bintray-andreamorimf</id>
        <name>bintray-andreamorimf</name>
        <url>http://dl.bintray.com/andreamorimf/readable</url>
    </repository>
    ...
</repositories>
```

## How to use it

In order to use the library, you can call (using a default org.w3c.dom.Document):

```java
ReadableContentExtractor extractor = new ReadableContentExtractor(document);
Element main = extractor.extract();
```

The result element there is a node that indicates the main content, plus a title, description and main image. You can also use:

```java
// Get the title string
String title = extractor.getTitle();

// Get the description string
String description = extractor.getDescription();

// Get the main image link (on OG meta tag or article image)
String imageLink = extractor.getMainImage();

// Get several image links on an article
Set<String> imageLinks = extractor.getMainImages(3);

// Get only element on main content, without title, description or image
Element main = extractor.getMainContent();
```

## Author

Andre Fonseca <andre.amorimfonseca@gmail.com>

## License

The MIT License 
