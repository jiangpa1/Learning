package com.jiangpa.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


@Data
@TableName("tb_category")
public class Category {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private String name;

    @TableLogic
    private Integer deleted;
}
