package com.travel.api.function.config;


import com.travel.api.function.entity.MiaoShaUser;
/**
 * @author 邱润泽 bullock
 */
public class UserContext {
	
	private static ThreadLocal<MiaoShaUser> userHolder = new ThreadLocal<MiaoShaUser>();
	
	public static void setUser(MiaoShaUser user) {
		userHolder.set(user);
	}
	
	public static MiaoShaUser getUser() {
		return userHolder.get();
	}

	public static void removeUser() {
		userHolder.remove();
	}

}