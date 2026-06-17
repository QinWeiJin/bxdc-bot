package com.lobsterai.skillgateway.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lobsterai.skillgateway.entity.PythonSandbox;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface PythonSandboxMapper extends BaseMapper<PythonSandbox> {

    default Optional<PythonSandbox> findByName(String name) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<PythonSandbox>()
                .eq(PythonSandbox::getName, name)));
    }

    default List<PythonSandbox> findByEnabledTrueOrderByNameAsc() {
        return selectList(new LambdaQueryWrapper<PythonSandbox>()
                .eq(PythonSandbox::getEnabled, 1)
                .orderByAsc(PythonSandbox::getName));
    }
}
