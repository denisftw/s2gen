# s2gen - static site generator

This is a simple static site generator written in Scala for my web-site [appliedscala.com](http://appliedscala.com/). It assumes that you write the content in Markdown and use Freemarker as a template engine. The generator supports watching for file changes, and it is completely unopinionated about organizing front-end assets.

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
The default settings are mostly fine, but feel free to change the `site.host`.
In order to generate the site, simply type:

```
$ s2gen
[22:43:29.760] [INFO ] SiteGenerator - Cleaning previous version of the site
[22:43:29.765] [INFO ] SiteGenerator - Generation started
[22:43:29.977] [INFO ] SiteGenerator - Successfully generated: <archive>
[22:43:29.979] [INFO ] SiteGenerator - Successfully generated: <sitemap>
[22:43:29.980] [INFO ] SiteGenerator - Successfully generated: <index>
[22:43:29.981] [INFO ] SiteGenerator - Successfully generated: <about>
[22:43:29.983] [INFO ] SiteGenerator - Registering a file watcher
[22:43:29.985] [INFO ] SiteGenerator - Successfully generated: content/hello-world.md
[22:43:29.986] [INFO ] SiteGenerator - Generation finished
[22:43:30.018] [INFO ] SiteGenerator - Waiting for changes...
```

After generating, **s2gen** switches to the monitor mode and starts waiting for file changes. 
If you don't need the monitor mode, you can start **s2gen** with the `-once` flag. 
In this case, the site is generated only once, after which **s2gen** quits.

Generated HTML files will be placed to the site directory.
Frontend assets (styles, scripts, images, fonts) could also be added
to this directory manually, and **s2gen** will not touch them.

### Custom templates

The bootstrap example generates the About page as a custom template. In order to add a custom template,
you must place the Freemarker file in the `templates` directory and add it to the `templates.custom` list in `s2gen.conf`:

```json
{
  "templates": {
    "custom": ["about.ftl"]
  }
}
```

For `about.ftl`, the output HTML file will be placed into `./site/about/index.html`, so it can be referenced as follows:

```html
<a href="/about">About</a>
```

Custom templates also have access to common site properties like `title` and `description`.

## Testing the site

In order to test the site in the browser, you can use a NodeJS based HTTP server:

```
$ npm install http-server -g
$ http-server -p 8080 site
Starting up http-server, serving site
Available on:
  http:127.0.0.1:8080
  http:192.168.1.103:8080
Hit CTRL-C to stop the server
```

## Copyright and License

Licensed under the MIT License, see the LICENSE file.
