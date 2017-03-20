package com.xiami.entity;

import java.sql.Connection;

public interface SqlEntity {
	public boolean save2DB(Connection conn);
}
