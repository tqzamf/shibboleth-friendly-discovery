Shibboleth Friendly Discovery
=============================

What it is
----------

... a Shibboleth Discovery Service optimized for best usability.
this means:

* IdPs selection by large, easy-to-aim-for buttons (no more tiny
  dropdowns)
* shows logos for all IdPs to aid identification
	* auto-generated random logos if necessary
* adaptively tries to guess most likely IdP
	* least recently used (cookie)
	* most popular by /16 (IPv4) or /48 (IPv6) IP block
	* global popularity
* fallback list of all IdPs is searchable
* many links are bookmarkable
* can be embedded in application
* will only show IdPs that the SP accepts (obviously)

other features:

* easy to deploy (Tomcat webapp)
* one instance can serve multiple SPs (as long as their list of accepted
  IdPs is identical)
* very scalable (>4k concurrent connections with a 150M heap, on a
  standard developer laptop)
* no requirements for embedding, except that the page must not use the
  namespaces `shibboleth-discovery*` (CSS, HTML IDs, Cookies) and
  `shibbolethDiscovery*` (JavaScript)
* embedding doesn't require jQuery (can use DOM methods), but will use
  jQuery if present


How to deploy
-------------

0. build the servlet. it's a Maven build; just do

	```bash
	mvn clean install
	```
	
	... and it will be in `target/shib.disco-*.war`.

1. create the database. this either means talking to the DBA, or:

	```bash
	sudo -u postgres createuser -D -I -S shibdisco
	sudo -u postgres psql shibdisco \
		-c "alter user shibdisco password 'secret'"
	sudo -u postgres createdb shibdisco
	```

	non-PostgreSQL databases should be possible.

2. create the tables. for PostgreSQL, this means executing the statements
	from `tables.sql`:
	
	```bash
	sudo -u postgres psql shibdisco -f tables.sql
	```

	other databases may require slightly different column types, but
	`tables.sql` should be relatively standard. don't omit the index or
	performance will be bad.

3. grant necessary permissions (`SELECT`, `INSERT`, `UPDATE` and `DELETE`).
	may involve the DBA again; if not:

	```bash
	sudo -u postgres psql shibdisco \
		-c "grant select, insert, update, delete on loginstats to shibdisco"
	```
	
	it isn't necessary for the user to have any other privileges. in
	particular, a standard least privileges configuration should not
	grant DDL commands (`CREATE TABLE`, `DROP TABLE`, ...) if practical.
	note that there is currently no actual need for the `UPDATE`
	permission, but it doesn't allow anything that `DELETE` + `INSERT`
	doesn't already permit anyway.

4. deploy the servlet, by copying it into Tomcat's `webapps` directory
	and adding the database connector for the database to be used to the
	`lib` directory (which on Ubuntu has to be created first):

	```bash
	sudo cp shib.disco-*.war /var/lib/tomcat7/webapps/shib.disco.war
	sudo mkdir -p /var/lib/tomcat7/lib
	sudo cp postgresql-*.jar /var/lib/tomcat7/lib/
	```

	it makes sense to drop the version in the `webapps` directory,
	because the filename determines the context path: if the file is
	called `shib.disco.war`, the servlet will be running at `shib.disco`.

5. configure the servlet and restart Tomcat. this involves editing its
	context configuration and setting context parameters:
	
	```bash
	sudo vim /var/lib/tomcat7/conf/Catalina/localhost/shib.disco.xml
	sudo service tomcat7 restart
	```

	see below for parameters. if auto-deployment is active, the file will
	be auto-created; otherwise it's in `/META-INF/context.xml` in the WAR.

6. configure Apache proxying to Tomcat, eg. using:

	```
	<Location /shibboleth>
		ProxyPass http://localhost:8080/shib.disco
		ProxyPassReverse http://localhost:8080/shib.disco
	</Location>
	```
	
	this isn't strictly necessary, but can be easier than setting up
	Tomcat for SSL. also, Tomcat cannot safely listen on port 80 (or 443),
	so this proxying is standard practice anyway.

	it may be necessary to change Tomcat's port from 8080 (if something
	else is already using that port) by changing the `<Connector/>` tag
	in `/etc/tomcat7/server.xml`, eg.
	
	```
	<Connector port="8000" address="127.0.0.1" protocol="HTTP/1.1"
		connectionTimeout="20000" URIEncoding="UTF-8"
		redirectPort="8443" />
	```

	this is the case for ruby on rails apps, which use 8080. in this case,
	it will also be necessary to disable Passenger in the discovery proxy,
	ie. the above example changes to:

	```
	<Location /shibboleth>
		PassengerEnabled off
		ProxyPass http://localhost:8000/shib.disco
		ProxyPassReverse http://localhost:8000/shib.disco
	</Location>
	```

7. modify your application to embed the discovery. the webapp serves a
	javascript snippet at `discovery/embed`, which will locate the element
	with `id="shibboleth-discovery"` and replace that with the discovery
	buttons. the simplest way of embedding the discovery is:

	```html
	<script src=".../discovery/embed" type="text/javascript"
		id="shibboleth-discovery" defer="defer"></script>
	```
	
	`type="text/javascript"` is the default in HTML5 and could be omitted.
	`defer="defer"` could also be omitted but is recommended: without it,
	the site will block while loading the javascript snippet.

	the code above this will only work if the discovery serves only one
	SP, which can be configured as the default `login` and `target`
	values. otherwise, add explicit `login` and `target` values as shown
	below.

	make sure there is an alternative way to initiate Shibboleth login
	when javascript is disabled. see `/index.html` in the WAR for an
	example of a login button that will be replaced with the discovery if
	javascript is enabled. essentially, the trick is to declare the
	element before the `<script>` tag, and call it `shibboleth-discovery`,
	ie. `id="shibboleth-discovery"`:
	
	```html
	<div id="shibboleth-discovery">
		<a href="/shibboleth/discovery/full" class="fallback">
			<img src="/shibboleth/shibboleth.png" />
			<p>log in with Shibboleth</p></a>
		<script src="/shibboleth/discovery/embed" type="text/javascript"
			defer="defer"></script>
	</div>
	```

	note that the CSS namespace `shibboleth-discovery` is not used to
	style the fallback button, to avoid interaction with discovery.

8. make sure the `DiscoveryFeed` handler is enabled in all Shibboleth
	instances that send discovery requests to the servlet. the servlet
	will download the list of accepted IdPs from `DiscoFeed` (relative
	to the `Login` URL). if this URL returns an error, the servlet will
	just show all IdPs -- even those that the SP will not accept, which is
	bad user experience. the relevant part in `shibboleth2.xml` is:

	```xml
	<Handler type="DiscoveryFeed" Location="/DiscoFeed"/>
	```


Discovery services
------------------

relative to the servlet root, the following discovery services are
available:

* `discovery/full`: an alphabetical list of all IdPs, as large buttons,
	along with a javascript-based search/filter box that performs a simple
	substring match on each of the terms the user enters.

* `discovery/friendly`: the "friendly" discovery, which only shows a
	configurable number of "most likely" IdPs to the user, as well as an
	"other IdPs" button that redirects to `discovery/full`.
	the "most likely" IdPs are, in order:

	1. the one the user picked last time, if any, as marked by a cookie
	2. the most popular ones for the user's /16 (IPv4) or /48 (IPv6)
	3. the globally most popular ones

* `discovery/embed`: a javascript snippet that embeds `discovery/friendly`,
	replacing the element with `id="shibboleth-discovery"` (which can be
	the `<script>` tag that embeds the script, or any other element
	declared before the `<script>` tag). for correct operation, the
	embedding page must not use the namespaces `shibboleth-discovery*`
	(CSS, HTML IDs, Cookies) and `shibbolethDiscovery*` (JavaScript).
	the snippet will use jQuery to do the embedding if jQuery is present
	in the embedding page, and fall back to plain old DOM methods if no
	jQuery is found.

each of the discovery method takes the following parameters:

* `entityID`: the entityID of the SP. strongly recommended. it is possible
	to set a default in the configuration (`shibboleth.default.sp`), but
	if the discovery is used for more than a single service provider,
	misconfigured services will show a discovery that logs the user into
	the wrong service, which is bad for usability.
	
* `target`: the URL to visit after Shibboleth login handler. it's usually
	better to instead set the `homeURL` in `<ApplicationDefaults>` in
	`shibboleth2.xml` to that URL and just omit the parameter.

	the value depends on the application; it is generally either a
	Shibboleth-protected starting page, or a URL that triggers the
	application's internal login mechanism.

	for the `omniauth-shibboleth` gem (ruby on rails), it is something like
	`https://host.name/users/auth/shibboleth/callback` (which then triggers
	an OAuth login using Shibboleth).

* `return`: passed by Shibboleth when calling the discovery indirectly,
	ie. when declared in `<SSO discoveryURL="...">` in the Shibboleth
	config. it is recommended to embed the discovery into the application
	instead because it saves a click for the user.

* most of the other parameters defined in the SAML2 "Identity Provider
	Discovery Service Protocol and Profile" specification. `returnIDParam`
	and `isPassive` are fully supported, but `policy` is ignored and always
	treated as if it was the default.


Configuration
-------------

* `jdbc.database`: resource declaration for the database connection. for
	the database connection to work, `auth` and `type` must be `Container`
	and `javax.sql.DataSource`, respectively.

	* `driverClassName`: generally just `org.postgresql.Driver` for
		PostgreSQL. this driver class must be available to Tomcat, ie.
		its JAR must be somewhere in the `lib` directory.

	* `url`: the JDBC connection URL, without username and password, but
		including any other options you would like to set, especially
		`ssl=true`. see the PostgreSQL (or other database= documentation
		for the full syntax, or just use PostgreSQL and adapt the example
		`jdbc:postgresql://localhost/shibdisco?ssl=true`.
		note that because the context config file is an XML document, any
		`&` characters in the URL will have to be escaped to `&amp;`.

	* `username`, `password`: database login credentials

	* `maxActive`, `maxIdle`, ...: these limit the number of connections,
		if desired. the servlet uses extensive caching, so increasing
		these values will likely not improve performance much.

	* `closeMethod`: generally just `close` so that connections are
		actually closed when they are disposed of.

	* `validationQuery`, `testOnBorrow`: to guard against timed-out
		connections, `testOnBorrow` should be `true` and `validationQuery`
		a fast, simple query that always succeeds, such as `SELECT 1` for
		PostgreSQL. this is required because the servlet relies on not
		getting dead connections from the pool. it will retry once if the
		connection dies during use, but if it gets another dead connection
		when it retries, it will just give up.

* `shibboleth.metadata.url`: an absolute URL pointing to the Shibboleth
	XML metadata. this file will be downloaded and parsed whenever it
	changes, or every 15 minutes if the server ignores `If-Modified-Since`.
	this must be a secure URL, ie. either `https://` or `localhost`.
	note that this is downloaded with Apache http-client and thus doesn't
	support the `file:///` scheme.

* `shibboleth.storageservice.prefixes`: if Shibboleth is configured to use
	a `StorageService` (the default), then it can generate `target=`
	parameters of the form `ss:mem:gibberish`, which cannot be bookmarked.
	in order for the discovery to recognize these URLs (so we don't
	recommend bookmarking them), their prefixes have to be given in a
	space-separated list. unless you changed this in the Shibboleth config,
	the correct value is `ss:mem:`.

* `shibboleth.default.sp`: entityID of a service provider to use if none
	is given in the URL when calling the discovery. if this parameter is
	omitted or set to the empty string, requests without an entityID will
	simply return an error.

	if the discovery is used for more than a single service provider,
	it is strongly recommended to not set a default. otherwise,
	misconfigured services could show a discovery that logs the user into
	the wrong service, which is bad for usability.

* `discovery.web.root`: external base URL under which the servlet is
	reachable. this is generally `https://host.name/shibboleth/`, but
	could also be set to `https://host.name:8443/shib.disco/` for an
	un-proxied Tomcat. should at least be an absolute path; it has to be
	a fully qualified URL if the discovery is ever embedded into a page
	not hosted on the same server as the discovery.

* `discovery.friendly.idps`: number of IdPs to show in the "friendly"
	(short) discovery. `6` is a good value.
