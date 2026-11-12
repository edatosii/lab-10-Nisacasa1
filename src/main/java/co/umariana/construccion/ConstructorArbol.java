package co.umariana.construccion;

import co.umariana.modelo.ArbolGenealogico;
import co.umariana.modelo.ArbolInvalidoException;
import co.umariana.modelo.Persona;
import co.umariana.modelo.Posicion;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Construye un árbol genealógico a partir de un texto: cada línea es una persona.
 *
 *     posicion;nombre;nacimiento;fallecimiento
 *
 * Ejemplo (el fallecimiento puede quedar vacío):
 *
 *     1;John Doe;2002;
 *     2;Henry Doe;1920;1990
 *     3;Maria Doe;1921;
 *     1.1;Timmy Doe;2021;
 *     1.2;Amy Doe;2026;
 *
 * INSTRUCCIONES PARA EL ESTUDIANTE (Etapa 3)
 * ------------------------------------------
 * Reemplace el "throw new UnsupportedOperationException" por su implementación. Esta clase usa lo que ya
 * hizo en las etapas anteriores (Posicion, Persona y ArbolGenealogico). NO modifique las pruebas (src/test).
 *
 * REGLAS
 *   1. Si el texto es null o no tiene ninguna línea con contenido: ArbolInvalidoException (mensaje: "El texto está vacío...").
 *   2. Las líneas pueden terminar en \n o en \r\n. Las líneas en blanco se ignoran, pero los números de línea
 *      que se informan cuentan TODAS las líneas del texto (la primera es la línea 1).
 *   3. Cada línea tiene 4 campos separados por ';' (el cuarto, el fallecimiento, puede estar vacío). Se acepta
 *      también la línea con solo 3 campos y sin el último ';'. Los campos se recortan (trim).
 *      Si hay menos de 3 o más de 4 campos: error.
 *   4. El nacimiento y el fallecimiento deben ser números enteros (el fallecimiento vacío es "no tiene").
 *   5. Cada persona debe ser válida (Persona.validar) y no puede haber dos líneas con la misma posición.
 *   6. Debe existir la persona "1" (el sujeto), pero puede estar en cualquier línea.
 *   7. Las líneas pueden venir en CUALQUIER orden. El constructor debe agregarlas al árbol en un orden en el
 *      que cada persona ya tenga a la que necesita (por ejemplo, primero los ascendientes por número Ahnentafel
 *      ascendente y los descendientes por generación). Las reglas del árbol (ver ArbolGenealogico) las valida el árbol.
 *   8. Todos los errores asociados a una línea se informan con ArbolInvalidoException y un mensaje que EMPIEZA con
 *      "Línea N: " (N es el número de línea de la persona con el problema), por ejemplo:
 *          "Línea 3: la posición 2 está repetida."
 *      Los errores que no son de una línea (texto vacío, falta el sujeto) no llevan ese prefijo.
 *   9. Solo se muestran los miembros que están en el texto: no se crean personas vacías para los que faltan.
 */
public final class ConstructorArbol {

    private ConstructorArbol() {
    }

    /**
     * Convierte el texto en un árbol (ver las reglas arriba).
     *
     * @throws ArbolInvalidoException si el texto no cumple las reglas
     */
    public static ArbolGenealogico construir(String texto) {
        if (texto == null) {
            throw new ArbolInvalidoException(TEXTO_VACIO);
        }

        // 1) Leer cada línea con contenido y convertirla en una persona válida, recordando su número de línea.
        String[] lineas = texto.split("\n", -1);
        List<Linea> leidas = new ArrayList<>();
        Linea sujeto = null;
        for (int i = 0; i < lineas.length; i++) {
            if (lineas[i].isBlank()) {
                continue;
            }
            Linea linea = new Linea(i + 1, leerPersona(i + 1, lineas[i]));
            String posicion = linea.persona.getPosicion();
            for (Linea anterior : leidas) {
                if (anterior.persona.getPosicion().equals(posicion)) {
                    throw error(linea.numero, "la posición " + posicion + " está repetida.");
                }
            }
            leidas.add(linea);
            if (Posicion.esSujeto(posicion)) {
                sujeto = linea;
            }
        }

        if (leidas.isEmpty()) {
            throw new ArbolInvalidoException(TEXTO_VACIO);
        }
        if (sujeto == null) {
            throw new ArbolInvalidoException("Falta el sujeto: debe haber una línea con la posición 1.");
        }

        // 2) Ordenar para que cada persona se agregue después de la que necesita: primero los ascendientes por
        //    número Ahnentafel (el hijo n / 2 siempre es menor) y luego los descendientes por generación.
        leidas.remove(sujeto);
        leidas.sort(Comparator.comparingInt(ConstructorArbol::ordenDeLlegada));

        // 3) Armar el árbol. Las reglas (hijo o progenitor existente, años) las valida el propio árbol.
        ArbolGenealogico arbol = new ArbolGenealogico(sujeto.persona);
        for (Linea linea : leidas) {
            try {
                arbol.agregar(linea.persona);
            } catch (IllegalArgumentException e) {
                throw error(linea.numero, e.getMessage());
            }
        }
        return arbol;
    }

    private static final String TEXTO_VACIO = "El texto está vacío: escriba al menos la línea del sujeto (posición 1).";

    /** Una persona leída del texto junto con el número de la línea en que estaba. */
    private static final class Linea {
        final int numero;
        final Persona persona;

        Linea(int numero, Persona persona) {
            this.numero = numero;
            this.persona = persona;
        }
    }

    /** Convierte una línea "posicion;nombre;nacimiento;fallecimiento" en una persona válida. */
    private static Persona leerPersona(int numero, String linea) {
        String[] campos = linea.split(";", -1);
        if (campos.length < 3 || campos.length > 4) {
            throw error(numero, "debe tener el formato posicion;nombre;nacimiento;fallecimiento.");
        }
        String posicion = campos[0].trim();
        String nombre = campos[1].trim();
        int nacimiento = leerAnio(numero, campos[2].trim(), "nacimiento");
        String textoFallecimiento = campos.length == 4 ? campos[3].trim() : "";
        Integer fallecimiento = textoFallecimiento.isEmpty() ? null : leerAnio(numero, textoFallecimiento, "fallecimiento");

        Persona persona = new Persona(posicion, nombre, nacimiento, fallecimiento);
        try {
            persona.validar();
        } catch (IllegalArgumentException e) {
            throw error(numero, e.getMessage());
        }
        return persona;
    }

    private static int leerAnio(int numero, String texto, String campo) {
        try {
            return Integer.parseInt(texto);
        } catch (NumberFormatException e) {
            throw error(numero, "el año de " + campo + " debe ser un número entero, no \"" + texto + "\".");
        }
    }

    /** Ascendientes primero (por su número Ahnentafel, hasta 2047) y después los descendientes por generación. */
    private static int ordenDeLlegada(Linea linea) {
        String posicion = linea.persona.getPosicion();
        if (Posicion.esAscendiente(posicion)) {
            return Integer.parseInt(posicion);
        }
        return Posicion.MAX_AHNENTAFEL + Posicion.generacion(posicion);
    }

    private static ArbolInvalidoException error(int numero, String mensaje) {
        return new ArbolInvalidoException("Línea " + numero + ": " + mensaje);
    }
}
