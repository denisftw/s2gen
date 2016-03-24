<div id="blog-single">
	<div class="blog-single__blog-post">
			<div class="blog-single__blog-post__title">
				<h1><#escape x as x?xml>${content.title}</#escape></h1>
			</div>
			<div class="blog-single__blog-post__date">
				<p><em>${content.date}</em></p>
			</div>
	</div>
	<div class="blog-single__blog-post__content">
		${content.body}
	</div>
<div>
