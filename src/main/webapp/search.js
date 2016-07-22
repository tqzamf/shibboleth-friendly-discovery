$(function() {
	var box = $(document.createElement("input"));
	box.attr("id", "shibboleth-discovery-search");
	// if the text box is empty, put in some notice about its purpose.
	// most users should figure that out on their own, but let's make
	// sure web 1.0 users also find out.
	box.focus(function(event) {
		if ($(this).data("dummy")) {
			$(this).data("dummy", false);
			$(this).css("color", "black");
			$(this).val("");
		}
	});
	box.blur(function(event) {
		if ($(this).val().trim() == "") {
			$(this).val("type to filter...");
			$(this).css("color", "gray");
			$(this).data("dummy", true);
		}
	});
	// the actual filtering logic
	box.bind("change keyup keydown paste", function(event) {
		// handling ENTER keypress in search box: click the single
		// element if exactly one is left, else color the box red
		// until the user edits something.
		if (event.type == "keydown" && event.which == 13) {
			var items = $("a.shibboleth-discovery-button:visible");
			if (items.length == 1)
				items[0].click();
			else
				$(this).css("color", "red");
			return false;
		} else if (event.type != "keyup" || event.which != 13)
			$(this).css("color", "");

		var keywords = $(this).val().trim().toLowerCase().split(/\s+/);
		var items = $("a.shibboleth-discovery-button");
		// unhide all items before hiding some of them during filtering.
		// note that if there are no keywords, this will unhide all
		// items.
		items.css("display", "block");
		items.each(function() {
			var text = $("p", this).text().toLowerCase();
			for (var i = 0; i < keywords.length; i++) {
				// hide all items whose displayName doesn't contain the
				// keyword. any item which is ever hidden stays hidden,
				// so this implements an AND search.
				if (text.indexOf(keywords[i]) < 0) {
					$(this).css("display", "none");
					break;
				}
			}
		});
		// hide all but the first 6 buttons
		$("a.shibboleth-discovery-button:visible")
				.slice(shibbolethDiscoverySearchLimit)
				.css("display", "none");
		// unhide the "others" button. technically unnecessary because
		// when searching, the full list is actually shown already, but
		// keeping it is less disruptive to the UI and may be more
		// reassuring to the user.
		$("#shibboleth-discovery-others").css("display", "block");
	});
	// pre-focus the box, so that the user just has to start typing.
	// this is to maximize usability for regular users. it means that
	// new users may not notice the functionality even exists, but they
	// will if they ever un-focus the box.
	$("#shibboleth-discovery-wayf").after(box);
	box.focus();
});
