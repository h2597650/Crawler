# -*- coding:utf-8 -*-
import re
import time
import log, config, util
from os import path

LOG = log.get_logger("zxLogger")


if config.LANG.upper() == 'CN':
    import i18n.msg_cn as msg
else:
    import i18n.msg_en as msg

Song_Type={0:'unknown',1:u"虾米", 2:u"网易"}

class Handler(object):
    """
    handler class encapsulate xiami or netease object
    """
    def __init__(self, proxy_pool = None):
        self.proxy_pool = proxy_pool
        self.need_proxy_pool = self.proxy_pool != None
        self.HEADERS = {}

class History(object):
    """
    download history entity
    """
    def __init__(self, song=None):
        self.id        = 0
        self.song_id   = song.song_id if song else 0
        self.song_name = song.song_name if song else None
        self.hq        = song.is_hq if song else 0
        self.source    = song.song_type if song else None
        self.location  = song.abs_path if song else None
        self.api_url   = song.dl_link if song else None
        self.dl_time   = None
        self.times     = 1

    def to_csv(self):
        return u"%d;%s;%s;%s;%s;%s;%s;%s;%d" %\
               (self.id,self.song_id,self.song_name,\
                'H' if self.hq == 1 else 'L', \
                msg.head_xm if self.source ==1 else msg.head_163, \
                self.location, self.api_url, self.last_dl_time_str(), \
                self.times)

    def last_dl_time_str(self):
        return re.sub(':[^:]*$','',str(self.dl_time))

    def __repr__(self):
        #TODO
        pass

class Song(object):
    """
    General Song class, if song_json was given, 
    the group_dir and abs_path of the object needs to be set by the caller
    """


    def __init__(self):
        self.song_type=0
        self.url = ''
        self.group_dir = ''
        self.song_id = ''
        self.song_name=''
        self.dl_link = ''
        self.success=False #if the song is downloaded successfully

        # lyrics link,text,filename, path etc.
        self.lyric_link = ''
        self.lyric_text = ''
        self.lyric_filename = ''
        self.lyric_abs_path = ''

        # artist_name
        self.artist_name = ''
        # album  name
        self.album_name = ''

        #used only for album/collection etc. create a dir to group all songs
        self.group_dir = None
        self.track_no = 1

        self.filename = ''
        
    def type_txt(self):
        return Song_Type[self.song_type]

    def post_set(self):
        """ set type_txt, filename, abs_path """
        if self.song_name:
            artist_part = (self.artist_name + u"_") if self.artist_name  else ""
            self.filename = self.song_name + u'.mp3'
            self.lyric_filename = artist_part + self.song_name  + u'.lrc'

            #replace slash if there is
            self.filename = self.filename.replace('/','_')
            self.lyric_filename = self.lyric_filename.replace('/','_')
            
            if self.group_dir:
                self.abs_path = path.join(config.DOWNLOAD_DIR, self.group_dir, self.filename)
                self.lyric_abs_path = path.join(config.DOWNLOAD_DIR, self.group_dir, self.lyric_filename)
            else:
                # abs path for mp3 and lyric
                self.abs_path = path.join(config.DOWNLOAD_DIR,self.filename)
                self.lyric_abs_path = path.join(config.DOWNLOAD_DIR,self.lyric_filename)
