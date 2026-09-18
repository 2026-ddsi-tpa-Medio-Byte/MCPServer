package ar.edu.utn.dds.k3003.mcp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dónde corren las consultas que se hacen en paralelo.
 *
 * <p>Un pedido a un módulo pasa casi todo su tiempo esperando la red. El pool común de Java tiene
 * tantos hilos como núcleos, así que veinte consultas «en paralelo» se harían de a tres o cuatro y
 * el plazo de cada herramienta no alcanzaría. Con hilos virtuales cada pedido espera en el suyo
 * sin ocupar un hilo del sistema.
 */
final class Hilos {

  static final ExecutorService ESPERA = Executors.newVirtualThreadPerTaskExecutor();

  private Hilos() {}
}
