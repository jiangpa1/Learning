package com.jiangpa.service;



import com.jiangpa.dto.UserLoginDTO;
import com.jiangpa.dto.UserRegisterDTO;
import com.jiangpa.dto.UserRoleUpdateDTO;
import com.jiangpa.dto.UserUpdateDTO;
import com.jiangpa.vo.UserVO;

import java.util.List;


public interface UserService {

    UserVO register(UserRegisterDTO dto);

    UserVO selectUser(Long id, Long userId, Integer role);

    List<UserVO> selectList();

    void update(UserUpdateDTO dto, Long id, Long userId);

    void delete(Long id, Long userId, Integer role);

    UserVO authenticate(UserLoginDTO dto);

    void updateRole(UserRoleUpdateDTO userRoleUpdateDTO);
}
