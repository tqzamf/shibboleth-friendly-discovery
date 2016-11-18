function shibbolethDiscovery(base, searchLimit, html) {
	if ("undefined" == typeof jQuery)
		// missing jQuery. the fallback button must do.
		return;

	jQuery(function() {
		// create tag containing discovery HTML snippet
		var container = jQuery(document.createElement("div"));
		container.attr("id", "shibboleth-discovery");
		container.html(html);

		// loading full discovery with turbolinks breaks the search
		if ("undefined" != typeof container.setAttribute)
			container.setAttribute("data-no-turbolink", "true");

		// replace "shibboleth-discovery" with HTML snippet. this allows
		// for graceful failure when there is no javascript.
		jQuery("#shibboleth-discovery").replaceWith(container);

		// now try to enable search as well
		shibbolethDiscoverySearchLimit = searchLimit;
		jQuery.ajax({
			url: base + "/search.js",
			dataType: "script",
			cache: true
		});
	});
}
