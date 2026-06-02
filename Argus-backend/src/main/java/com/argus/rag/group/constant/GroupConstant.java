package com.argus.rag.group.constant;

/**
 * 群组模块常量
 */
public class GroupConstant {

    public static final int MAX_GROUP_NAME_LENGTH = 128;
    public static final int MAX_GROUP_DESCRIPTION_LENGTH = 512;
    public static final String NON_MEMBER_MESSAGE = "当前用户不是目标群组成员";
    public static final String NON_OWNER_MESSAGE = "当前用户不是目标群组 OWNER";
    public static final String EXISTING_MEMBER_MESSAGE = "被邀请人已是群组成员";
    public static final String EXISTING_PENDING_INVITATION_MESSAGE = "已存在待处理邀请";
}
