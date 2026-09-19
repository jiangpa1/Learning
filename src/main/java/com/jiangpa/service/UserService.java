package com.jiangpa.service;



import com.jiangpa.common.PageResult;
import com.jiangpa.dto.PageQueryDTO;
import com.jiangpa.dto.UpdatePasswordDTO;
import com.jiangpa.dto.UserLoginDTO;
import com.jiangpa.dto.UserRegisterDTO;
import com.jiangpa.dto.UserRoleUpdateDTO;
import com.jiangpa.dto.UpdateNicknameDTO;
import com.jiangpa.vo.UserVO;


public interface UserService {

    UserVO register(UserRegisterDTO dto);

    UserVO selectUser(Long id, Long userId, Integer role);

    PageResult<UserVO> selectList(PageQueryDTO pageQueryDTO);

    void updateNickname(UpdateNicknameDTO dto, Long id, Long userId);

    void delete(Long id, Long userId, Integer role);

    UserVO authenticate(UserLoginDTO dto);

    void updateRole(UserRoleUpdateDTO userRoleUpdateDTO);

    void updatePassword(UpdatePasswordDTO updatePasswordDTO, Long id, Long userId);
}
