package com.ieltsstudio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ieltsstudio.entity.Exam;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExamMapper extends BaseMapper<Exam> {

    @Select("SELECT * FROM exams WHERE user_id = #{userId} AND deleted = 0 ORDER BY created_at DESC")
    List<Exam> findByUserId(Long userId);

    @Select("<script>" +
            "SELECT * FROM exams WHERE user_id = #{userId} AND deleted = 0" +
            "<if test='type != null'> AND type = #{type}</if>" +
            "<if test='search != null'> AND (title LIKE CONCAT('%',#{search},'%') OR description LIKE CONCAT('%',#{search},'%'))</if>" +
            " ORDER BY created_at DESC LIMIT #{offset}, #{size}" +
            "</script>")
    List<Exam> findByUserIdPaged(@Param("userId") Long userId,
                                @Param("type") String type,
                                @Param("search") String search,
                                @Param("offset") int offset,
                                @Param("size") int size);

    @Select("<script>" +
            "SELECT COUNT(*) FROM exams WHERE user_id = #{userId} AND deleted = 0" +
            "<if test='type != null'> AND type = #{type}</if>" +
            "<if test='search != null'> AND (title LIKE CONCAT('%',#{search},'%') OR description LIKE CONCAT('%',#{search},'%'))</if>" +
            "</script>")
    int countByUserIdFiltered(@Param("userId") Long userId,
                             @Param("type") String type,
                             @Param("search") String search);

    @Select("SELECT * FROM exams WHERE deleted = 0 AND status = 'ready' ORDER BY created_at DESC LIMIT #{limit}")
    List<Exam> findPublicExams(int limit);

    @Select("SELECT COUNT(*) FROM exams WHERE user_id = #{userId} AND title = #{title} AND deleted = 0")
    int countByUserIdAndTitle(Long userId, String title);

    @Delete("DELETE FROM exams WHERE id = #{id} AND user_id = #{userId}")
    int physicalDeleteById(@Param("id") Long id, @Param("userId") Long userId);
}
