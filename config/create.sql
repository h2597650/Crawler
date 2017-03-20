drop table if exists artists;
create table artists (
	artist_id int(12),
	str_id varchar(15),
	name varchar(255),
	play_cnt int(12),
	fans_cnt int(12),
	comments_cnt int(12),
	primary key (artist_id),
	unique index(str_id)
) engine=MyISAM;

drop table if exists albums;
create table albums (
	album_id int(12),
	str_id varchar(15),
	name varchar(255),
	play_cnt int(12),
	collect_cnt int(12),
	comments_cnt int(12),
	score double(5,2),
	rank5 int(12),
	rank4 int(12),
	rank3 int(12),
	rank2 int(12),
	rank1 int(12),
	artist_id int(12),
	primary key (album_id),
	unique index(str_id),
	index(artist_id)
) engine=MyISAM;

drop table if exists songs;
create table songs (
	song_id int(12),
	str_id varchar(15),
	name varchar(255),
	play_cnt int(12),
	share_cnt int(12),
	comments_cnt int(12),
	album_id int(12),
	primary key (song_id),
	unique index(str_id),
	index(album_id)
) engine=MyISAM;

drop table if exists artist_simlar;
create table artist_simlar (
	artist_id int(12),
	simlar_id int(12),
	primary key (artist_id,simlar_id),
	index(artist_id),
	index(simlar_id)
) engine=MyISAM;

drop table if exists genres;
create table genres (
	genre_id int(12),
	content varchar(50),
	primary key (genre_id)
) engine=MyISAM;

drop table if exists artist_genre;
create table artist_genre (
	artist_id int(12),
	genre_id int(12),
	primary key (artist_id,genre_id),
	index(artist_id),
	index(genre_id)
) engine=MyISAM;

drop table if exists album_genre;
create table album_genre (
	album_id int(12),
	genre_id int(12),
	primary key (album_id,genre_id),
	index(album_id),
	index(genre_id)
) engine=MyISAM;

drop table if exists tags;
create table tags (
	tag_id int(12),
	content varchar(50),
	primary key (tag_id)
) engine=MyISAM;

drop table if exists artist_tag;
create table artist_tag (
	artist_id int(12),
	tag_id int(12),
	primary key (artist_id,tag_id),
	index(artist_id),
	index(tag_id)
) engine=MyISAM;

drop table if exists album_tag;
create table album_tag (
	album_id int(12),
	tag_id int(12),
	primary key (album_id,tag_id),
	index(album_id),
	index(tag_id)
) engine=MyISAM;

drop table if exists song_tag;
create table song_tag (
	song_id int(12),
	tag_id int(12),
	primary key (song_id,tag_id),
	index(song_id),
	index(tag_id)
) engine=MyISAM;

drop table if exists song_artist;
create table song_artist (
	song_id int(12),
	artist_id int(12),
	primary key (song_id,artist_id),
	index(song_id),
	index(artist_id)
) engine=MyISAM;
