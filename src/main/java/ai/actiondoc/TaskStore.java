package ai.actiondoc;
import static ai.actiondoc.Models.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
@Service
public class TaskStore {
    private final String url;
    public TaskStore(@Value("${actiondoc.db}") String path) throws Exception {
        Path db = Path.of(path).toAbsolutePath(); Files.createDirectories(db.getParent()); url = "jdbc:sqlite:" + db;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, analysis_id TEXT NOT NULL, finding_id TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, due_date TEXT, status TEXT NOT NULL, source_name TEXT NOT NULL, quote TEXT NOT NULL, page INTEGER NOT NULL, created_at TEXT NOT NULL, UNIQUE(analysis_id, finding_id))");
        }
    }
    private Connection connect() throws SQLException { Connection c = DriverManager.getConnection(url); try (Statement s = c.createStatement()) { s.execute("PRAGMA busy_timeout=5000"); } return c; }
    public synchronized Task create(Analysis analysis, Document doc, Finding finding, String title, String description, String dueDate, boolean approved) throws SQLException {
        if (!approved) throw new IllegalArgumentException("내용 확인과 등록 승인이 필요합니다.");
        if (!finding.category().equals("action")) throw new IllegalArgumentException("추천 조치만 작업으로 등록할 수 있습니다.");
        title = title == null ? "" : title.strip(); description = description == null ? "" : description.strip();
        if (title.isEmpty() || title.length() > 160 || description.length() > 4000) throw new IllegalArgumentException("제목은 1~160자, 설명은 4,000자 이하여야 합니다.");
        if (dueDate != null && dueDate.isBlank()) dueDate = null;
        if (dueDate != null) { try { LocalDate.parse(dueDate); } catch (Exception e) { throw new IllegalArgumentException("올바른 기한을 입력해 주세요."); } }
        Task task = new Task(UUID.randomUUID().toString(),analysis.id(),finding.id(),title,description,dueDate,"TODO",doc.name(),finding.quote(),finding.page(),Instant.now().toString());
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("INSERT INTO tasks VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(analysis_id,finding_id) DO NOTHING")) {
            p.setString(1,task.id()); p.setString(2,task.analysisId()); p.setString(3,task.findingId()); p.setString(4,task.title()); p.setString(5,task.description()); p.setString(6,task.dueDate()); p.setString(7,task.status()); p.setString(8,task.sourceName()); p.setString(9,task.quote()); p.setInt(10,task.page()); p.setString(11,task.createdAt()); p.executeUpdate();
        }
        return list().stream().filter(t -> t.analysisId().equals(analysis.id()) && t.findingId().equals(finding.id())).findFirst().orElseThrow();
    }
    public List<Task> list() throws SQLException {
        List<Task> tasks = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT * FROM tasks ORDER BY created_at DESC")) {
            while (r.next()) tasks.add(new Task(r.getString("id"),r.getString("analysis_id"),r.getString("finding_id"),r.getString("title"),r.getString("description"),r.getString("due_date"),r.getString("status"),r.getString("source_name"),r.getString("quote"),r.getInt("page"),r.getString("created_at")));
        } return tasks;
    }
    public void update(String id, String status) throws SQLException {
        if (!Set.of("TODO","DONE").contains(status)) throw new IllegalArgumentException("올바른 작업 상태를 선택해 주세요.");
        try (Connection c = connect(); PreparedStatement p = c.prepareStatement("UPDATE tasks SET status=? WHERE id=?")) {
            p.setString(1,status); p.setString(2,id); if (p.executeUpdate() == 0) throw new NoSuchElementException("작업을 찾을 수 없습니다.");
        }
    }
}
