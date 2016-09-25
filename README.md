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
[15:20:11.513] [INFO ] S2Generator - Cleaning previous version of the site
[15:20:11.518] [INFO ] S2Generator - Generation started
[15:20:11.707] [INFO ] S2Generator - Successfully generated: <archive>
[15:20:11.709] [INFO ] S2Generator - Successfully generated: <sitemap>
[15:20:11.711] [INFO ] S2Generator - Successfully generated: <index>
[15:20:11.712] [INFO ] S2Generator - Successfully generated: <about>
[15:20:11.722] [INFO ] S2Generator - Successfully generated: 2016/hello-world.md
[15:20:11.724] [INFO ] S2HttpServer - Starting the HTTP server
[15:20:11.727] [INFO ] S2Generator - Generation finished
[15:20:11.738] [INFO ] log - Logging initialized @980ms
[15:20:11.771] [INFO ] Server - jetty-9.3.11.v20160721
[15:20:11.845] [INFO ] AbstractConnector - Started ServerConnector@562457e1{HTTP/1.1}{0.0.0.0:8080}
[15:20:11.845] [INFO ] Server - Started @1090ms
[15:20:11.845] [INFO ] S2HttpServer - The HTTP server has been started on port 8080
[15:20:11.845] [INFO ] S2Generator - Registering a file watcher
[15:20:12.135] [INFO ] S2Generator - Waiting for changes...
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

**s2gen** comes with an embedded Jetty server, which starts automatically in the monitor mode and serves static content from the output directory.
This is more than enough for testing, so you don't need to install anything else.

## Copyright and License

Licensed under the MIT License, see the LICENSE file.
