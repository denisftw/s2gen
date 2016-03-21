# s2gen - static site generator

This is a simple static site generator written in Scala for [my web-site](http://appliedscala.com/). It assumes that you write the content in Markdown and use Freemarker as a template engine. The generator supports watching for file changes and is completely unopinionated about organizing front-end assets.

## Tutorial

The example configuration can be found in `conf/application.conf` (HOCON format) or below:

```
directories {
  basedir = "."
  content = "content"
  output = "site"
  archive = "blog"
  templates = "templates"
}
templates {
  post = "post.ftl"
  archive = "archive.ftl"
  sitemap = "sitemap.ftl"
  index = "index.ftl"
}
site {
  host = "http://appliedscala.com"
  lastmod = "2016-02-22"
}
```

 The main idea is to specify the base directory. Other directories are expected to be relative to it.
