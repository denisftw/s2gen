<!DOCTYPE html>
<html>
  <head>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
    <meta charset="utf-8">
    <meta name="description" content="${site.description}">
    <meta http-equiv="content-language" content="en-us">

    <#if pageType??>
      <#if pageType == "index">
        <title>Home - ${site.title}</title>
      <#elseif pageType == "archive">
        <title>Blog - ${site.title}</title>
      <#elseif pageType == "post" && content.title??>
        <title>${content.title} - ${site.title}</title>
      <#else>
        <title>${site.title}</title>
      </#if>
    <#else>
      <title>${site.title}</title>
    </#if>

    <link href="/css/styles.css" rel="stylesheet">
