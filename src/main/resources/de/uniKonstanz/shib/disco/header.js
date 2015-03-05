function shibbolethDiscovery(base, html) {
	var insert = function() {
		// insert the CSS, as a DOM element
		var link = document.createElement("link");
		link.rel = "stylesheet";
		link.type = "text/css";
		link.href = base + "/disco.css";
		document.getElementsByTagName("head")[0].appendChild(link);
		// create tag containing discovery HTML snippet
		var target = document.getElementById("shibboleth-discovery");
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
		target.parentNode.replaceChild(container, target);
	}
	
	if ("undefined" != typeof jQuery)
		// jquery, present on most pages these days
		jQuery(insert);
	else if ("undefined" != typeof document.addEventListener)
		// all modern browsers
		document.addEventListener("DOMContentLoaded", insert);
	else if ("undefined" != typeof document.attachEvent)
		// IE8 legacy
		document.attachEvent("onreadystatechange", function(){
			if (document.readyState === "complete")
				insert();
		});
}
