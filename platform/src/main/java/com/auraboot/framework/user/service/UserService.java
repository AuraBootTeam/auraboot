package com.auraboot.framework.user.service;

import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.exception.UserException;

import java.util.Collection;
import java.util.List;

/**
 * @author 高海军 帝奇 Apr 6, 2015 5:07:09 PM
 */
public interface UserService {


    public User signUp(String email, String rawPassword, String displayName) throws UserException;

    default User signUp(String email, String rawPassword) throws UserException {
        return signUp(email, rawPassword, null);
    }

    public User signIn(String email, String password) throws UserException;


    public void signOut(Long userId) ;


    public User findByUserId(Long userId);

    public User findByUserName(String userName);


    User findByEmail(String email);

    public void update(User user);

    /**
     * 根据业务ID查询用户
     * @param pid 业务ID
     * @return 用户信息
     */
    User findByPid(String pid);

    /**
     * Batch find users by IDs.
     */
    List<User> findByUserIds(Collection<Long> userIds);
}
