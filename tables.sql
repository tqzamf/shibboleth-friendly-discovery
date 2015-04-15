create table loginstats(
	iphash integer not null,
	entityid varchar(255) not null,
	count integer not null,
	created integer not null);
create index loginstats_entityid
	on loginstats(iphash, entityid);
