package com.ieltsstudio.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ieltsstudio.entity.ErrorBook;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ErrorBookMapper extends BaseMapper<ErrorBook> {

    @Select("SELECT * FROM error_book WHERE user_id = #{userId} AND mastered = 0 ORDER BY created_at DESC")
    List<ErrorBook> findUnmasteredByUserId(Long userId);

    @Select("SELECT COUNT(*) FROM error_book WHERE user_id = #{userId} AND mastered = 0")
    int countUnmasteredByUserId(Long userId);

    @Insert("<script>" +
            "INSERT INTO error_book (user_id, exam_id, question_id, user_answer, correct_answer, review_count, mastered) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.userId}, #{item.examId}, #{item.questionId}, #{item.userAnswer}, #{item.correctAnswer}, #{item.reviewCount}, #{item.mastered})" +
            "</foreach>" +
            "</script>")
    int insertBatch(List<ErrorBook> list);
}
