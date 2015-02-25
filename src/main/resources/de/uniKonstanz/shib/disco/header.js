function shibbolethDiscovery(base, html) {
	// insert the CSS
	var link = document.createElement("link");
	link.rel = "stylesheet";
	link.type = "text/css";
	link.href = base + "/disco.css";
	document.getElementsByTagName("head")[0].appendChild(link);
	// insert the discovery HTML itself. uses insertAdjacentHTML if
	// possible, but falls back to good old innerHTML.
	var target = document.getElementById("shibboleth-discovery");
	var container = document.createElement("div");
	container.id = "shibboleth-discovery";
	if (container.insertAdjacentHTML)
		container.insertAdjacentHTML("afterBegin", html);
	else
		container.innerHTML = html;
	target.parentNode.replaceChild(container, target);
}
