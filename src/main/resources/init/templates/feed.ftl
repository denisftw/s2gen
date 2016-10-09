<?xml version="1.0"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
    <channel>
        <title>${site.title}</title>
        <link>${site.siteHost}</link>
        <atom:link href="${site.siteHost}/feed.xml" rel="self" type="application/rss+xml" />
        <description>${site.description}</description>
        <pubDate>${site.lastPubDateJ?string["EEE, d MMM yyyy HH:mm:ss Z"]}</pubDate>
        <lastBuildDate>${site.lastBuildDateJ?string["EEE, d MMM yyyy HH:mm:ss Z"]}</lastBuildDate>

    <#list posts as post>
        <#if (post.status == "published")>
            <item>
                <title><#escape x as x?xml>${post.title}</#escape></title>
                <link>${site.siteHost}/${post.link}</link>
                <pubDate>${post.dateJ?string["EEE, d MMM yyyy HH:mm:ss Z"]}</pubDate>
                <guid isPermaLink="false">${site.siteHost}/${post.link}</guid>
                <description>${post.previewText}</description>
            </item>
        </#if>
    </#list>
    </channel>
</rss>
