drop table if exists artists;
create table artists (
	artist_id int(12),
	str_id varchar(15),
	name varchar(255),
	play_cnt int(12),
	fans_cnt int(12),
	comments_cnt int(12),
	primary key (artist_id)
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
	primary key (album_id)
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
	primary key (song_id)
) engine=MyISAM;

drop table if exists artist_similar;
create table artist_similar (
	artist_id int(12),
	similar_id int(12),
	primary key (artist_id,similar_id)
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
	primary key (artist_id,genre_id)
) engine=MyISAM;

drop table if exists album_genre;
create table album_genre (
	album_id int(12),
	genre_id int(12),
	primary key (album_id,genre_id)
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
	primary key (artist_id,tag_id)
) engine=MyISAM;

drop table if exists album_tag;
create table album_tag (
	album_id int(12),
	tag_id int(12),
	primary key (album_id,tag_id)
) engine=MyISAM;

drop table if exists song_tag;
create table song_tag (
	song_id int(12),
	tag_id int(12),
	primary key (song_id,tag_id)
) engine=MyISAM;

drop table if exists song_artist;
create table song_artist (
	song_id int(12),
	artist_id int(12),
	primary key (song_id,artist_id)
) engine=MyISAM;
