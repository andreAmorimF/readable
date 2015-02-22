Readable
========

Yet another readability library for Java. It works great for news and blog sites.

Check out it working on: http://brreadable.herokuapp.com/

## Installation

### Maven

Maven artifacts can be fetched on the following repository:

```xml
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

Andre Fonseca <andre.amorimfonseca at gmail.com>

## License

The MIT License 
