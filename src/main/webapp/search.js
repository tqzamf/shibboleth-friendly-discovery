jQuery(function() {
	var box = jQuery(document.createElement("input"));
	box.attr("id", "shibboleth-discovery-search");
	// if the text box is empty, put in some notice about its purpose.
	// most users should figure that out on their own, but let's make
	// sure web 1.0 users also find out.
	box.focus(function(event) {
		if (jQuery(this).data("dummy")) {
			jQuery(this).data("dummy", false);
			jQuery(this).css("color", "black");
			jQuery(this).val("");
		}
	});
	box.blur(function(event) {
		if (jQuery(this).val().trim() == "") {
			jQuery(this).val("type to filter...");
			jQuery(this).css("color", "gray");
			jQuery(this).data("dummy", true);
		}
	});
	// the actual filtering logic
	box.bind("change keyup keydown paste focus blur", function(event) {
		// handling ENTER keypress in search box: click the single
		// element if exactly one is left, else color the box red
		// until the user edits something.
		if (event.type == "keydown" && event.which == 13) {
			var items = jQuery("a.shibboleth-discovery-button:visible")
					.not(jQuery("#shibboleth-discovery-others"));
			if (items.length == 1)
				items[0].click();
			else
				jQuery(this).css("color", "red");
			return false;
		} else if (event.type != "keyup" || event.which != 13)
			jQuery(this).css("color", "");

		// only use filter text if it isn't the dummy text.
		var keywords;
		if (!jQuery(this).data("dummy"))
			keywords = jQuery(this).val().trim().toLowerCase().split(/\s+/);
		else
			keywords = "";
		var items = jQuery("a.shibboleth-discovery-button");
		// unhide all items before hiding some of them during filtering.
		// note that if there are no keywords, this will unhide all
		// items.
		items.css("display", "block");
		items.each(function() {
			var text = jQuery("p", this).text().toLowerCase();
			for (var i = 0; i < keywords.length; i++) {
				// hide all items whose displayName doesn't contain the
				// keyword. any item which is ever hidden stays hidden,
				// so this implements an AND search.
				if (text.indexOf(keywords[i]) < 0) {
					jQuery(this).css("display", "none");
					break;
				}
			}
		});
		// hide all but the first 6 buttons
		jQuery("a.shibboleth-discovery-button:visible")
				.slice(shibbolethDiscoverySearchLimit)
				.css("display", "none");
		// unhide the "others" button. technically unnecessary because
		// when searching, the full list is actually shown already, but
		// keeping it is less disruptive to the UI and may be more
		// reassuring to the user.
		jQuery("#shibboleth-discovery-others").css("display", "block");
	});
	// pre-focus the box, so that the user just has to start typing.
	// this is to maximize usability for regular users. it means that
	// new users may not notice the functionality even exists, but they
	// will if they ever un-focus the box.
	jQuery("#shibboleth-discovery-wayf").after(box);
	box.focus();
});
