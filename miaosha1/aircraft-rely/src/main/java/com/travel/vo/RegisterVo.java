package com.travel.vo;

import com.travel.commons.valiadator.CheckMobile;
import lombok.*;
import org.hibernate.validator.constraints.Length;

import javax.validation.constraints.NotNull;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class RegisterVo {
    @NotNull
    @CheckMobile
    private Long mobile ;

    @NotNull
    @Length(min=32)
    private String password;

    @NotNull
    private String nickname;

    private String salt;

    private String head;

}
