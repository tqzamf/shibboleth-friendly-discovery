create table loginstats(
	iphash integer,
	entityid varchar(255),
	count integer,
	created timestamp);
create index loginstats_entityid
	on loginstats(iphash, entityid);
