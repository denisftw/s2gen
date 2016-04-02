# s2gen - static site generator

This is a simple static site generator written in Scala for my web-site [appliedscala.com](http://appliedscala.com/). It assumes that you write the content in Markdown and use Freemarker as a template engine. The generator supports watching for file changes and is completely unopinionated about organizing front-end assets.

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
├── s2gen.conf
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

The example configuration can be found in `s2gen.conf` (HOCON format).
The default settings are mostly fine, but feel free to change the `site.host`.
In order to generate the site, simply type:

```
$ s2gen
[11:05:53.508] [INFO ] SiteGenerator - Cleaning previous version of the site
[11:05:53.515] [INFO ] SiteGenerator - Generation started
[11:05:53.599] [INFO ] SiteGenerator - Generating the archive page
[11:05:53.662] [INFO ] SiteGenerator - The archive page was generated
[11:05:53.663] [INFO ] SiteGenerator - Generating the sitemap
[11:05:53.664] [INFO ] SiteGenerator - The sitemap was generated
[11:05:53.664] [INFO ] SiteGenerator - Generating the index page
[11:05:53.665] [INFO ] SiteGenerator - The index page was generated
[11:05:53.665] [INFO ] SiteGenerator - Generating custom pages
[11:05:53.667] [INFO ] SiteGenerator - All custom pages were generated
[11:05:53.706] [INFO ] SiteGenerator - Successfully generated: 2016/hello-world.md
[11:05:53.706] [INFO ] SiteGenerator - Generation finished
[11:05:53.706] [INFO ] SiteGenerator - Registering a file watcher
[11:05:53.745] [INFO ] SiteGenerator - Waiting for changes...
```

After generating, **s2gen** switches to the monitor mode and starts waiting for file changes.
Generated HTML files will be placed to the site directory.
Frontend assets (styles, scripts, images, fonts) could also be added
to this directory manually and **s2gen** will not touch them.

### Custom templates

The bootstrap example generates the About page as a custom template. In order to add a custom template,
you must place the Freemarker file in the `templates` directory and add it to the `templates.custom` list in `s2gen.conf`:

```
templates {
  custom = ["about.ftl"]
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
