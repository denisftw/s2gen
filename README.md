# s2gen - static site generator

This is a simple static site generator written in Scala for my web-site [appliedscala.com](http://appliedscala.com/). It assumes that you write the content in Markdown and use Freemarker as a template engine. The generator supports watching for file changes and is completely unopinionated about organizing front-end assets.

## Tutorial

The example configuration can be found in `s2gen.conf` (HOCON format).
The main idea here is to specify the base directory.
Other directories are expected to be relative to it.

Your version of this file must be placed in the root directory of your project.
In this directory, provided that `s2gen` is in `PATH`, you can start the generator by typing `s2gen`.
It will attempt to generate HTML files and switch to the the monitor mode.
In order to stop the generator, press `Ctrl/Cmd+C`.