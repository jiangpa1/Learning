package com.jiangpa.service;



import com.jiangpa.dto.UserLoginDTO;
import com.jiangpa.dto.UserRegisterDTO;
import com.jiangpa.dto.UserUpdateDTO;
import com.jiangpa.vo.UserVO;

import java.util.List;


public interface UserService {

    UserVO register(UserRegisterDTO dto);

    UserVO selectUser(Long id);

    List<UserVO> selectList();

    void update(UserUpdateDTO dto, Long id);

    void delete(Long id);

    String login(UserLoginDTO dto);

}
