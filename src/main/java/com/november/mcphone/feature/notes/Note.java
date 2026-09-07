package com.november.mcphone.feature.notes;

/**
 * 一条便签（服务端持久化的数据模型，客户端/网络层共用）。
 * 与旧版 {@code client.NotesStore.Note} 的区别：没有 File 字段，id 由服务端分配。
 */
public final class Note {

    /** 客户端新建便签时传的 id，真正的 id 由服务端分配。 */
    public static final int NEW_ID = 0;

    /** 便签条数上限（服务端封死，伪造包塞不进更多）。 */
    public static final int MAX_COUNT = 200;
    /** 标题长度上限（与便签页输入框 maxLength 一致）。 */
    public static final int MAX_TITLE = 32;
    /** 正文长度上限（与便签页 TextArea maxLength 一致）。 */
    public static final int MAX_BODY = 2000;

    public int id;
    public String title;
    public String body;
    /** 最后修改时刻（毫秒），列表按它倒序。 */
    public long modified;

    public Note() {
        this(NEW_ID, "", "");
    }

    public Note(int id, String title, String body) {
        this.id = id;
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
        this.modified = System.currentTimeMillis();
    }

    public boolean isNew() {
        return id == NEW_ID;
    }

    /** 标题和正文都为空白即视为空便签（保存时等价于删除）。 */
    public boolean isBlank() {
        return title.trim().isEmpty() && body.trim().isEmpty();
    }
}
