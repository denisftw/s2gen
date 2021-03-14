# s2gen - static site generator

This is a simple static site generator written in Scala for my web-site [appliedscala.com](http://appliedscala.com/). It uses Freemarker as a template engine and assumes that you write the content in Markdown. The generator supports the monitor mode, comes with an embedded Web server and stays completely unopinionated about organizing front-end assets.

It should work well on any operating system provided that JRE8 is installed.

## Getting started

Download the latest release from [the release page](https://github.com/denisftw/s2gen/releases/latest)

Extract the downloaded package to any directory and add `s2gen`
executable to the `PATH`. To achieve that, you can edit your `.bashrc`:

```bash
S2GEN=/home/user/DevTools/s2gen
PATH=$S2GEN/bin:$PATH
```

Initialize a skeleton project in an empty directory by typing the following:

```
$ s2gen -init
```

The following directory structure will be generated:

```
$ tree -L 4
.
├── content
│   └── blog
│       └── 2016
│           └── hello-world.md
├── s2gen.json
├── site
│   └── css
│       └── styles.css
└── templates
    ├── about.ftl
    ├── archive.ftl
    ├── blog.ftl
    ├── footer.ftl
    ├── header.ftl
    ├── index.ftl
    ├── info.ftl
    ├── main.ftl
    ├── menu.ftl
    ├── page.ftl
    ├── post.ftl
    └── sitemap.ftl

6 directories, 13 files

```

The example configuration can be found in `s2gen.json`.
The default settings are mostly fine, but feel free to change the `site.host` and `server.port` properties.
In order to generate the site, simply type `s2gen`:

```
$ s2gen
[15:21:41.415] [INFO ] GenerationService - Generation started
[15:21:41.606] [INFO ] PageGenerationService - Cleaning up the previous version
[15:21:41.775] [INFO ] PageGenerationService - Successfully generated: <archive> 
[15:21:41.785] [INFO ] PageGenerationService - Successfully generated: <index> 
[15:21:41.801] [INFO ] PageGenerationService - Successfully generated: <about> 
[15:21:41.806] [INFO ] PageGenerationService - Successfully generated: <feed.xml> 
[15:21:41.806] [INFO ] PageGenerationService - Successfully generated: <sitemap.xml> 
[15:21:41.823] [INFO ] PageGenerationService - Successfully generated: 2016/hello-world.md 
[15:21:41.887] [INFO ] HttpServerService - Starting the HTTP server
[15:21:41.950] [INFO ] HttpServerService - The HTTP server has been started on port 8080
[15:21:42.267] [INFO ] MonitorService - Registering a file watcher
[15:21:42.289] [INFO ] MonitorService - Waiting for changes...
```

After generating, **s2gen** switches to the monitor mode and starts waiting for file changes.

It also starts an embedded Jetty server on a port specified in `s2gen.json` as the `server.port` property.
If you don't want to start a server in the monitor mode, start **s2gen** with the `-noserver` flag.

If you don't need the monitor mode, you can start **s2gen** with the `-once` flag. 
In this case, the site is generated only once, after which **s2gen** quits.

Generated HTML files will be placed to the site directory.
Frontend assets (styles, scripts, images, fonts) could also be added
to this directory manually, and **s2gen** will not touch them.

### Custom templates

The bootstrap example generates the About page as a custom template. In order to add a custom template,
you must place the Freemarker file in the `templates` directory and add it to the `templates.custom` list in `s2gen.json`:

```json
{
  "templates": {
    "custom": ["about.ftl"],
    "customXml": ["feed.ftl", "sitemap.ftl"]
  }
}
```

For `about.ftl`, the output HTML file will be placed into `./site/about/index.html`, so it can be referenced as follows:

```html
<a href="/about">About</a>
```

For `feed.ftl` and `sitemap.ftl`, the output XML files will be put into the `./site` directory.

Custom templates also have access to common site properties like `title` and `description`.

## Testing the site

**s2gen** comes with an embedded Jetty server, which starts automatically in the monitor mode and serves static content from the output directory.
This is more than enough for testing, so you don't need to install anything else.

## Available template values

Inside the template, you will have access to the following values as they are defined in `s2gen.json`:

|reference|description|
|---------|-----------|
|`site.title`|The title of the site|
|`site.description`|The description of the site|
|`site.host`|The host name including `http(s)` but excluding the trailing slash|
|`site.lastmod`|Tha last modification data of the site as a whole in `YYYY-MM-DD` format (for `sitemap`)|
|`site.title`|The title of the site|
|`currentLanguage`|The current language (only available in I18N mode)|

Posts have several more values available to them. In particular, the `date` and `status` properties are mandatory in the header section of Markdown content files, and they are available in templates as follows:

|reference|description|
|---------|-----------|
|`content.date`|The date of the post in `YYYY-MM-DD` format|
|`content.dateJ`|The date of the post as `java.util.Date`|
|`content.status`|The status of the post. The post is not added to the blog until it's `published`|

The following values are available automatically to all posts:

|reference|description|
|---------|-----------|
|`content.body`|The rendered HTML of the post|
|`content.preview`|The HTML version of the preview section (see the example for the preview markers)|
|`content.previewText`|The text version of the preview section (see the example for the preview markers)|

Custom templates (both XML and HTML) and the archive template have access to the following:

|reference|description|
|---------|-----------|
|`posts`|All published posts sorted by date as `java.util.List`|
|`site.lastPubDateJ`|The date of the last post as `java.util.Date`|
|`site.lastBuildDateJ`|The date of the last site generation as `java.util.Date`|


You can also add other values by putting custom keys to the header section of the Markdown file. 
They will be available in templates as well. For example the `title` property will be available as `content.title` and so on.


## I18N support

You can instruct **s2gen** to build localized variants of your Web-site alongside the default one. This way, the default version will be available at the root address `/`, whereas localized versions will be served from `/<langCode>`. 

In order to enable the I18N support, you need to create a directory called `i18n` and put it in `templates`. The `i18n` directory should contain files with message translations for your templates, for example,

* `default.properties`
* `ru.properties`
* `es.propserties`

These are Java properties files and they can contain UTF8 and HTML. Defined translations will be available in templates files with the `message.` prefix, so if you define a property called `main_slogan`, you can access it in a template using `${messages.main_slogan}`.

Templates will also have access to a special variable called `currentLanguage`. For localized versions, it will return the language code (the name of the `.properties` file). For the default version, it will return the empty string.

You can write posts in different languages. 
If you add to a post a special property called `language` and set to, say, 
`es`, then this post will be only available to the corresponding version 
of the Web-site. You don't need to add this property for posts 
in the *default* language, but you can do that - just make sure that 
you set it to empty string. Then you can use Freemarker conditions to render feed-like pages:

```text
  <#list posts as post>
    <#if post.status == "published" && ((post.language?? && 
        currentLanguage == post.language) ||
        (!post.language?? && currentLanguage == ""))>
    <li>
        <div class="excerpt">${post.previewText}</div>
    </li>
    </#if>
  </#list>
```

The same trick can be used for rendering archive pages and sitemaps.

## Markdown content in custom templates
If you want to generate one of your custom pages (i.e Privacy Policy) from Markdown, simply put necessary files in the `content/misc` directory and make sure your `md` file has at least:

* `title`
* `type` (must be anything but `post`)
* `language` (if not provided, the file will be considered to be written in `default` language

Then, assuming that the `title` is indeed `Privacy Policy`, inside of your Freemarker template, reference the content as follows:

```
<#if misc["Privacy Policy"]??>
  <#if misc["Privacy Policy"][currentLanguage]??>
    ${misc["Privacy Policy"][currentLanguage].body}
  <#else>
    ${misc["Privacy Policy"][""].body}
  </#if>
</#if>
```

The above code will check whether you Privacy Policy exists in the `currentLanguage` and fall back to using `default`.

## Copyright and License

Licensed under the MIT License, see the LICENSE file.
