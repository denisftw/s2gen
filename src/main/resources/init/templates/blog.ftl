<div class="blog-archive">
	<h1 class="page-title">Blog</h1>
  <#list posts as post>
      <#if (post.status == "published")>
        <div class="blog-archive__blog-post">
						<div class="blog-archive__blog-post__title">
							<a href="/${post.uri}"><span><#escape x as x?xml>${post.title}</#escape></span></a>
						</div>
						<div class="blog-archive__blog-post__date">
							<p><em>${post.date}</em></p>
						</div>
        </div>
        <div class=blog-archive__blog-post__content>
          ${post.body}
        </div>
      </#if>
    </#list>
</div>
