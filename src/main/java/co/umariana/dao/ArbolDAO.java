package co.umariana.dao;

import co.umariana.conexion.ConexionBD;
import co.umariana.modelo.ArbolGenealogico;
import co.umariana.modelo.Persona;
import co.umariana.modelo.ResumenArbol;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * DAO (Data Access Object): la única clase que habla con la base de datos. Guarda los árboles genealógicos
 * en las tablas "arboles" y "miembros" (ver db/schema.sql). Se construye igual que EstudianteDAO del laboratorio 9.
 *
 * INSTRUCCIONES PARA EL ESTUDIANTE (Etapa 4)
 * ------------------------------------------
 * 1. Cada método marcado con "TODO" lanza UnsupportedOperationException. Reemplace esa línea por su
 *    implementación con JDBC.
 * 2. NO modifique las pruebas (src/test). Las pruebas NO necesitan MySQL: usan una base de datos H2 en memoria.
 * 3. Esta etapa usa las anteriores: para guardar recorra arbol.miembros(); para cargar, reconstruya el árbol con
 *    new ArbolGenealogico(sujeto) y arbol.agregar(...).
 *
 * REGLAS (las pruebas las verifican)
 *  - Los métodos NO lanzan SQLException: la capturan, imprimen el mensaje con System.out.println y devuelven
 *    un valor "de error" (-1, false, lista vacía o null).
 *  - Use SIEMPRE PreparedStatement con parámetros (?), nunca concatene datos dentro del SQL.
 *  - Guardar un árbol son VARIAS sentencias (el árbol y todos sus miembros): hágalo en una TRANSACCIÓN
 *    (con.setAutoCommit(false); ... con.commit(); y con.rollback() si algo falla) para que nunca quede
 *    guardado "a medias". Antes de cerrar la conexión vuelva a dejar setAutoCommit(true).
 *  - El id de cada fila lo genera la base de datos (AUTO_INCREMENT).
 *  - Los miembros se guardan en el orden de arbol.miembros(): así, al leerlos ORDER BY id, cada persona
 *    aparece después de la que necesita para poder agregarla al árbol.
 */
public class ArbolDAO {

    private static final int MAX_NOMBRE = 100;

    private static final String INSERTAR_ARBOL = "INSERT INTO arboles (nombre) VALUES (?)";
    private static final String INSERTAR_MIEMBRO =
            "INSERT INTO miembros (arbol_id, posicion, nombre, nacimiento, fallecimiento) VALUES (?, ?, ?, ?, ?)";
    private static final String LISTAR =
            "SELECT a.id, a.nombre, a.creado, COUNT(m.id) AS total"
            + " FROM arboles a LEFT JOIN miembros m ON m.arbol_id = a.id"
            + " GROUP BY a.id, a.nombre, a.creado ORDER BY a.id";
    private static final String CARGAR =
            "SELECT posicion, nombre, nacimiento, fallecimiento FROM miembros WHERE arbol_id = ? ORDER BY id";
    private static final String EXISTE = "SELECT id FROM arboles WHERE id = ?";
    private static final String BORRAR_MIEMBROS = "DELETE FROM miembros WHERE arbol_id = ?";
    private static final String ELIMINAR = "DELETE FROM arboles WHERE id = ?";

    // =====================================================================
    // ETAPA 4.1. Guardar un árbol nuevo (INSERT en dos tablas, con transacción)
    // =====================================================================

    /**
     * Guarda un árbol nuevo: una fila en "arboles" (con el nombre) y una fila en "miembros" por cada persona.
     *
     * @return el id que la base de datos le dio al árbol; -1 si el nombre es nulo, está en blanco o tiene más
     *         de 100 caracteres, si el árbol es null, o si hubo un error (en ese caso NO queda nada guardado)
     */
    public long guardar(String nombre, ArbolGenealogico arbol) {
        if (nombre == null || nombre.isBlank() || nombre.length() > MAX_NOMBRE || arbol == null) {
            return -1;
        }
        try (Connection con = ConexionBD.conectar()) {
            con.setAutoCommit(false);
            try {
                long id;
                try (PreparedStatement ps = con.prepareStatement(INSERTAR_ARBOL, Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, nombre);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        rs.next();
                        id = rs.getLong(1);
                    }
                }
                insertarMiembros(con, id, arbol);
                con.commit();
                return id;
            } catch (SQLException e) {
                con.rollback();   // deshace también la fila de "arboles"
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.out.println("Error al guardar el árbol: " + e.getMessage());
            return -1;
        }
    }

    // =====================================================================
    // ETAPA 4.2. Listar (SELECT con JOIN y COUNT)
    // =====================================================================

    /**
     * @return un resumen por cada árbol guardado (id, nombre, cuántos miembros tiene y cuándo se creó),
     *         ordenados por id; lista vacía si no hay árboles o si hay un error
     */
    public List<ResumenArbol> listar() {
        List<ResumenArbol> lista = new ArrayList<>();
        try (Connection con = ConexionBD.conectar();
             PreparedStatement ps = con.prepareStatement(LISTAR);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                lista.add(new ResumenArbol(
                        rs.getLong("id"),
                        rs.getString("nombre"),
                        rs.getInt("total"),
                        rs.getTimestamp("creado").toLocalDateTime()));
            }
            return lista;
        } catch (SQLException e) {
            System.out.println("Error al listar los árboles: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // =====================================================================
    // ETAPA 4.3. Cargar un árbol (SELECT y reconstrucción del árbol)
    // =====================================================================

    /**
     * @return el árbol con ese id, reconstruido con todos sus miembros; null si no existe ese id o si hay un error
     */
    public ArbolGenealogico cargar(long id) {
        try (Connection con = ConexionBD.conectar();
             PreparedStatement ps = con.prepareStatement(CARGAR)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                ArbolGenealogico arbol = null;
                while (rs.next()) {
                    int f = rs.getInt("fallecimiento");
                    Integer fallecimiento = rs.wasNull() ? null : f;
                    Persona persona = new Persona(rs.getString("posicion"), rs.getString("nombre"),
                            rs.getInt("nacimiento"), fallecimiento);
                    // La primera fila es el sujeto; las demás vienen después de la persona que necesitan.
                    if (arbol == null) {
                        arbol = new ArbolGenealogico(persona);
                    } else {
                        arbol.agregar(persona);
                    }
                }
                return arbol;   // null si no hubo filas: ese árbol no existe
            }
        } catch (SQLException | IllegalArgumentException e) {
            // IllegalArgumentException: los datos guardados no forman un árbol válido.
            System.out.println("Error al cargar el árbol " + id + ": " + e.getMessage());
            return null;
        }
    }

    // =====================================================================
    // ETAPA 4.4. Actualizar (reemplazar los miembros de un árbol, con transacción)
    // =====================================================================

    /**
     * Guarda los cambios de un árbol ya existente (por ejemplo, después de agregar o eliminar miembros):
     * deja en la base de datos exactamente los miembros de {@code arbol}. El nombre del árbol NO cambia.
     *
     * @return true si se actualizó; false si no existe un árbol con ese id, si el árbol es null o si hubo un
     *         error (en ese caso NO se modifica nada: ni se borran los miembros anteriores)
     */
    public boolean actualizar(long id, ArbolGenealogico arbol) {
        if (arbol == null) {
            return false;
        }
        try (Connection con = ConexionBD.conectar()) {
            con.setAutoCommit(false);
            try {
                if (!existe(con, id)) {
                    con.rollback();
                    return false;
                }
                try (PreparedStatement ps = con.prepareStatement(BORRAR_MIEMBROS)) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                insertarMiembros(con, id, arbol);
                con.commit();
                return true;
            } catch (SQLException e) {
                con.rollback();   // los miembros anteriores se conservan
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        } catch (SQLException e) {
            System.out.println("Error al actualizar el árbol " + id + ": " + e.getMessage());
            return false;
        }
    }

    // =====================================================================
    // ETAPA 4.5. Eliminar (DELETE)
    // =====================================================================

    /**
     * Elimina el árbol y todos sus miembros (la base de datos borra los miembros sola: ON DELETE CASCADE).
     *
     * @return true si se eliminó; false si no existía ese id o si hubo un error
     */
    public boolean eliminar(long id) {
        try (Connection con = ConexionBD.conectar();
             PreparedStatement ps = con.prepareStatement(ELIMINAR)) {
            ps.setLong(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            System.out.println("Error al eliminar el árbol " + id + ": " + e.getMessage());
            return false;
        }
    }

    // =====================================================================
    // Métodos auxiliares (usan la conexión de quien los llama, para quedar dentro de su transacción)
    // =====================================================================

    /** Inserta todos los miembros del árbol en el orden de arbol.miembros(). */
    private void insertarMiembros(Connection con, long arbolId, ArbolGenealogico arbol) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(INSERTAR_MIEMBRO)) {
            for (Persona p : arbol.miembros()) {
                ps.setLong(1, arbolId);
                ps.setString(2, p.getPosicion());
                ps.setString(3, p.getNombre());
                ps.setInt(4, p.getNacimiento());
                if (p.getFallecimiento() == null) {
                    ps.setNull(5, Types.INTEGER);
                } else {
                    ps.setInt(5, p.getFallecimiento());
                }
                ps.executeUpdate();
            }
        }
    }

    private boolean existe(Connection con, long id) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(EXISTE)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
