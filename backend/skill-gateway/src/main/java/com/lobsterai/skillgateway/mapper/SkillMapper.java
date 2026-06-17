package com.lobsterai.skillgateway.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lobsterai.skillgateway.entity.Skill;
import com.lobsterai.skillgateway.entity.SkillVisibility;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SkillMapper extends BaseMapper<Skill> {

    default Optional<Skill> findByName(String name) {
        return Optional.ofNullable(selectOne(new LambdaQueryWrapper<Skill>().eq(Skill::getName, name)));
    }

    default List<Skill> findAllPublicSummary() {
        return selectList(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getVisibility, SkillVisibility.PUBLIC));
    }

    default List<Skill> findVisibleSummaryForUser(String userId) {
        return selectList(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getVisibility, SkillVisibility.PUBLIC)
                .or(w -> w.eq(Skill::getVisibility, SkillVisibility.PRIVATE)
                        .eq(Skill::getCreatedBy, userId)));
    }

    /**
     * 按可见性 + 所有者类型过滤（用于页面只查询用户技能）
     * @param userId 当前用户 ID
     * @param skillOwnerType 1: 用户技能, 2: 系统技能
     */
    default List<Skill> findVisibleSummaryForUserByOwnerType(String userId, Integer skillOwnerType) {
        if (userId == null || userId.trim().isEmpty()) {
            return selectList(new LambdaQueryWrapper<Skill>()
                    .eq(Skill::getVisibility, SkillVisibility.PUBLIC)
                    .eq(Skill::getSkillOwnerType, skillOwnerType));
        }
        return selectList(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillOwnerType, skillOwnerType)
                .and(w -> w.eq(Skill::getVisibility, SkillVisibility.PUBLIC)
                        .or(ww -> ww.eq(Skill::getVisibility, SkillVisibility.PRIVATE)
                                .eq(Skill::getCreatedBy, userId))));
    }

    /**
     * 按技能所有者类型查找技能
     * @param skillOwnerType 1: 用户技能, 2: 系统技能
     */
    default List<Skill> findBySkillOwnerType(Integer skillOwnerType) {
        return selectList(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillOwnerType, skillOwnerType));
    }

    /**
     * 按技能所有者类型查找启用的技能
     * @param skillOwnerType 1: 用户技能, 2: 系统技能
     */
    default List<Skill> findBySkillOwnerTypeAndEnabledIsTrue(Integer skillOwnerType) {
        return selectList(new LambdaQueryWrapper<Skill>()
                .eq(Skill::getSkillOwnerType, skillOwnerType)
                .eq(Skill::isEnabled, true));
    }
}
