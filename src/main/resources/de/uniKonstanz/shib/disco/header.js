function shibbolethDiscovery(base, html) {
	function insertDiscovery() {
		// insert the CSS, as a DOM element
		var link = document.createElement("link");
		link.rel = "stylesheet";
		link.type = "text/css";
		link.href = base + "/disco.css";
		document.getElementsByTagName("head")[0].appendChild(link);

		// create tag containing discovery HTML snippet
		var container = document.createElement("div");
		container.id = "shibboleth-discovery";
		if ("undefined" != typeof jQuery)
			// use jQuery if present
			jQuery(container).html(html);
		else if ("undefined" != typeof container.insertAdjacentHTML)
			// all modern browsers
			container.insertAdjacentHTML("afterBegin", html);
		else
			// older Firefoxes
			container.innerHTML = html;
		// loading full discovery with turbolinks breaks the search
		if ("undefined" != typeof container.setAttribute)
			container.setAttribute("data-no-turbolink", "true");

		// replace "shibboleth-discovery" with HTML snippet. this allows
		// for graceful failure when there is no javascript.
		var target = document.getElementById("shibboleth-discovery");
		target.parentNode.replaceChild(container, target);
	}

	if ("undefined" != typeof jQuery)
		// use jquery, present on most pages these days
		jQuery(insertDiscovery);
	else if (document.getElementById("shibboleth-discovery"))
		// page already loaded far enough; no need to wait.
		// important when loading asynchronously, because
		// DOMContentLoaded might already have been fired in this case.
		insertDiscovery();
	else if ("undefined" != typeof document.addEventListener)
		// all modern browsers: wait for page loaded
		document.addEventListener("DOMContentLoaded", insertDiscovery, false);
	else if ("undefined" != typeof document.attachEvent)
		// IE8 legacy
		document.attachEvent("onreadystatechange", function() {
			if (document.readyState === "complete")
				insertDiscovery();
		});
	// else give up. the fallback button must do.
}
