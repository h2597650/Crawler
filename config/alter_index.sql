alter table artists add unique(str_id);

alter table albums add unique(str_id);
alter table albums add index(artist_id);

alter table songs add unique(str_id);
alter table songs add index(album_id);

alter table artist_similar add index(artist_id);
alter table artist_similar add index(similar_id);

alter table artist_genre add index(artist_id);
alter table artist_genre add index(genre_id);

alter table album_genre add index(album_id);
alter table album_genre add index(genre_id);

alter table artist_tag add index(artist_id);
alter table artist_tag add index(tag_id);

alter table album_tag add index(album_id);
alter table album_tag add index(tag_id);

alter table song_tag add index(song_id);
alter table song_tag add index(tag_id);

alter table song_artist add index(song_id);
alter table song_artist add index(artist_id);
