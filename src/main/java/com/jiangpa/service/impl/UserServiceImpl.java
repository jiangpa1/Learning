package com.jiangpa.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;


import com.jiangpa.common.CacheKeys;
import com.jiangpa.dto.UserLoginDTO;
import com.jiangpa.dto.UserRegisterDTO;
import com.jiangpa.dto.UserRoleUpdateDTO;
import com.jiangpa.dto.UserUpdateDTO;
import com.jiangpa.exception.BusinessException;
import com.jiangpa.mapper.UserMapper;
import com.jiangpa.pojo.User;
import com.jiangpa.service.UserService;
import com.jiangpa.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


@Service
@Slf4j
public class UserServiceImpl implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(PasswordEncoder passwordEncoder, UserMapper userMapper, StringRedisTemplate stringRedisTemplate) {
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public UserVO register(UserRegisterDTO dto) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, dto.getUsername());
        User exists = userMapper.selectOne(wrapper);

        if (exists != null) {
            throw new BusinessException(400, "用户名已存在！");
        }

        if(dto.getNickname() ==  null || dto.getNickname().trim().isEmpty()) {
            dto.setNickname(dto.getUsername());
        }

        String encodedPassword = passwordEncoder.encode(dto.getPassword());

        User user = new User();

        user.setUsername(dto.getUsername());
        user.setPassword(encodedPassword);
        user.setNickname(dto.getNickname());
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());

        userMapper.insert(user);

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);

        return userVO;
    }


    @Override
    public UserVO selectUser(Long id, Long userId, Integer role) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在！");
        }

        if (!Objects.equals(id, userId) && !Objects.equals(role, 1)) {
            throw new BusinessException(403, "无权操作他人账号");
        }

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);

        return userVO;
    }

    @Override
    public List<UserVO> selectList() {
        List<User> users = userMapper.selectList(null);
        List<UserVO> userVOs = new ArrayList<>();
        for (User user : users) {
            UserVO userVO = new UserVO();
            BeanUtils.copyProperties(user, userVO);
            userVOs.add(userVO);
        }
        return userVOs;
    }

    @Override
    public void update(UserUpdateDTO dto, Long id, Long userId) {
        String nickname = dto.getNickname();
        LocalDateTime updateTime = LocalDateTime.now();

        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在！");
        }

        if (!user.getId().equals(userId)) {
            throw new BusinessException(403, "无权操作他人账号");
        }
        user.setNickname(nickname);
        user.setUpdateTime(updateTime);
        userMapper.updateById(user);
    }

    @Override
    public void delete(Long id, Long userId, Integer role) {
        User user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(404, "用户不存在！");
        }

        if (!user.getId().equals(userId) && !Objects.equals(role, 1)) {
            throw new BusinessException(403, "无权操作他人账号");
        }

        userMapper.deleteById(id);
    }

    @Override
    public UserVO authenticate(UserLoginDTO dto) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, dto.getUsername()));
        if (user == null) {
            throw new BusinessException(400, "用户名或密码错误");
        }

        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(400, "用户名或密码错误");
        }

        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);

        return userVO;
    }

    @Override
    public void updateRole(UserRoleUpdateDTO userRoleUpdateDTO) {
        User user = userMapper.selectById(userRoleUpdateDTO.getId());
        if (user == null) {
            throw new BusinessException(404, "用户不存在！");
        }

        Integer role = userRoleUpdateDTO.getRole();

        if (user.getRole() != null && user.getRole().equals(1)) {
            Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                    .eq(User::getRole, 1).ne(User::getId, userRoleUpdateDTO.getId()));
            if (count == 0) {
                throw new BusinessException(403, "无法降级最后一位管理员");
            }
        }
        user.setRole(role);
        userMapper.updateById(user);
        try {
            stringRedisTemplate.delete(CacheKeys.tokenRefresh(user.getId()));
        } catch (Exception e) {
            log.warn("删除 refresh key 失败，不影响此次返回", e);
        }
    }
}
