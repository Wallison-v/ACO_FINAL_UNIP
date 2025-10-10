import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;

/**
 * ACOVisualizer - implementação do ACO em Java com GUI em Swing.
 * Lê pontos de um CSV (x,y), executa o ACO e desenha:
 *  - o grafo (nós+arestas) e a melhor rota em cada iteração
 *  - gráfico da evolução da melhor distância por iteração
 */
public class ACOVisualizer {

    // -----------------------
    // CONFIGURAÇÕES 
    // -----------------------
    static final String DEFAULT_CSV = "pontos.csv"; // se cancelar o file chooser, tenta esse caminho relativo
    static final int START_INDEX = 0;      // ponto inicial (0-based)
    static final int N_FORMIGAS = 75;     // numero de formigas
    static final int N_ITERACOES = 100;  // numero de iteracoes
    static final double ALFA = 1.0;     // importância do feromônio
    static final double BETA = 2.0;     // importância da heurística 
    static final double EVAPORACAO = 0.9; // (1 - p) em algumas formulas aqui usamos "rho" como taxa de evaporação
    static final double Q = 100.0; // refoco do melhor caminho
    static final double ELITISMO = 2.0; // fator de reforço elitista
    static final int DELAY_MS = 300; // pausa entre iterações para animação
    static final double SOLUCAO_OTIMA = 2579.0; // A280 
    static final double LIMITE_MELHORA = 0.001; // 0,1% porcentagem de melhora nas iteracoes
    static final int LIMITE_ITER_SEM_MELHORA = 10; // limite de iteraçao sem melhora 

  
    static class Ponto {
        double x, y;
        Ponto(double x, double y) { this.x = x; this.y = y; }
    }

    static class Formiga {
        int atual;
        ArrayList<Integer> rota;
        boolean[] visitado;
        

        Formiga(int inicio, int nPontos) {
            this.atual = inicio;
            this.rota = new ArrayList<>();
            this.visitado = new boolean[nPontos];
            this.rota.add(inicio);
            this.visitado[inicio] = true;
        }

        void mover(int proximo) {
            this.atual = proximo;
            this.rota.add(proximo);
            this.visitado[proximo] = true;
        }
        
        boolean completouTour(int n) {
            return this.rota.size() == n;
        }
    }
  

    // -----------------------
    // Classe principal do ACO 
    // -----------------------
    static class ACO {
        List<Ponto> pontos;
        int n;
        double[][] dist;
        double[][] pher;
        Random rnd = new Random();

        ACO(List<Ponto> pontos) {
            this.pontos = pontos;
            this.n = pontos.size();
            this.dist = new double[n][n];
            this.pher = new double[n][n];
            initMatrizes();
        }

        private void initMatrizes() {
            for (int i = 0; i < n; ++i) {
                for (int j = 0; j < n; ++j) {
                    if (i == j) dist[i][j] = 0;
                    else {
                        double dx = pontos.get(i).x - pontos.get(j).x;
                        double dy = pontos.get(i).y - pontos.get(j).y;
                        dist[i][j] = Math.hypot(dx, dy);
                    }
                    // inicializa feromônio com valor pequeno > 0
                    pher[i][j] = 1.0;
                }
            }
        }

        // Escolha da próxima cidade com roleta viciada
        private int escolherProximo(int i, boolean[] visitado) {
            double[] pesos = new double[n];
            double soma = 0.0;
            for (int j = 0; j < n; ++j) {
                if (!visitado[j] && j != i) {
                    double tau = Math.pow(pher[i][j], ALFA);                   
                    double eta = Math.pow(1.0 / (dist[i][j] + 1e-6), BETA);
                    pesos[j] = tau * eta;
                    soma += pesos[j];
                } else {
                    pesos[j] = 0;
                }
            }
            if (soma == 0) { // se tudo zero, escolhe aleatório entre não visitados
                List<Integer> candidatos = new ArrayList<>();
                for (int j = 0; j < n; ++j) if (!visitado[j] && j != i) candidatos.add(j);
                return candidatos.get(rnd.nextInt(candidatos.size()));
            }
            // roleta
            double r = rnd.nextDouble() * soma;
            double acc = 0.0;
            for (int j = 0; j < n; ++j) {
                acc += pesos[j];
                if (acc >= r) return j;
            }
            // fallback
            for (int j = 0; j < n; ++j) if (!visitado[j] && j != i) return j;
            return -1;
        }
        // calcula distância total da rota (fechando o ciclo)
        
        double distanciaRota(List<Integer> rota) {
            double s = 0.0;
            for (int k = 0; k < rota.size() - 1; ++k) {
                s += dist[rota.get(k)][rota.get(k + 1)];
            }
            // volta para o início
            s += dist[rota.get(rota.size() - 1)][rota.get(0)];
            return s;
        }

        // executa uma rodada completa do ACO e retorna histórico de melhor por iteração
        List<Double> runAndReport(GraphPanel graphPanel, ChartPanel chartPanel) {
            double melhorDistancia = Double.POSITIVE_INFINITY;
            List<Integer> melhorRotaGlobal = null;
            List<Double> historico = new ArrayList<>(); 
            
            // parada adaptativa 
            
            double ultimaMelhorDistancia = Double.POSITIVE_INFINITY;
            int semMelhoraCount = 0;
            final double LIMITE_MELHORA = 0.001; // 0.1% de melhora mínima
            final int MAX_SEM_MELHORA = 15;       // parar após iterações sem melhora
            boolean paradaAntecipada = false;

            for (int it = 0; it < N_ITERACOES; ++it) {
                // cria formigas e faz cada formiga construir rota
                List<Formiga> col = new ArrayList<>();
                for (int f = 0; f < N_FORMIGAS; ++f) {
                    Formiga ant = new Formiga(START_INDEX, n);
                    while (!ant.completouTour(n)) {
                        int atual = ant.atual;
                        int proximo = escolherProximo(atual, ant.visitado);
                        if (proximo == -1) break; // segurança
                        ant.mover(proximo);
                       
                        
                    }
                    col.add(ant);
                }

                // avalia e atualiza melhor soluçao 
                for (Formiga ant : col) {
                    double d = distanciaRota(ant.rota);
                    if (d < melhorDistancia) {
                        melhorDistancia = d;
                        melhorRotaGlobal = new ArrayList<>(ant.rota);
                    }
                }
                
             // Evaporação global
                for (int i = 0; i < n; ++i) {
                    for (int j = 0; j < n; ++j) {
                        pher[i][j] *= (1.0 - EVAPORACAO);
                        if (pher[i][j] < 1e-6) pher[i][j] = 1e-6;
                    }
                }
                
                // Depósito proporcional (todas as formigas)
                for (Formiga ant : col) {
                    double d = distanciaRota(ant.rota);
                    double deposit = Q / d;
                    for (int k = 0; k < ant.rota.size() - 1; ++k) {
                        int a = ant.rota.get(k);
                        int b = ant.rota.get(k + 1);
                        pher[a][b] += deposit;
                        pher[b][a] += deposit; // grafo não-direcional
                    }
                    
                    // retorno ao inicio
                    int a = ant.rota.get(ant.rota.size() - 1);
                    int b = ant.rota.get(0);
                    pher[a][b] += deposit;
                    pher[b][a] += deposit;
                }
             //  Elitismo adaptativo
                if (melhorRotaGlobal != null) {
                	double pesoElite = 1.0 + (double) it / N_ITERACOES; // cresce ao longo das iterações
                    double depositElite = pesoElite * (Q / melhorDistancia);
                for (int k = 0; k < melhorRotaGlobal.size() - 1; ++k) {
                	int a = melhorRotaGlobal.get(k);
                	int b = melhorRotaGlobal.get(k + 1);
                	pher[a][b] += depositElite;
                	pher[b][a] += depositElite;
                	}
                int a = melhorRotaGlobal.get(melhorRotaGlobal.size() - 1);
                int b = melhorRotaGlobal.get(0); pher[a][b] += depositElite;
                pher[b][a] += depositElite;
                }

              historico.add(melhorDistancia);
              
              double gap = ((melhorDistancia - SOLUCAO_OTIMA) / SOLUCAO_OTIMA) * 100.0;
              
             
              System.out.print("Iteração " + (it + 1) + 
                      " - Melhor distância: " + String.format("%.1f", melhorDistancia) + 
                      " | GAP: " + String.format("%.2f", gap) + "% | Caminho: ");
              			if (melhorRotaGlobal != null) {
              			for (int c : melhorRotaGlobal) System.out.print((c + 1) + " ");
              			}
              			System.out.println();

                // Atualiza painéis (thread-safe)
                final List<Integer> bestRouteCopy = (melhorRotaGlobal == null) ? null : new ArrayList<>(melhorRotaGlobal);
                final double bestDistCopy = melhorDistancia;
                final double gapCopy = gap;
                final int iter = it + 1;
                SwingUtilities.invokeLater(() -> {
                    graphPanel.setState(pontos, bestRouteCopy, bestDistCopy, gapCopy, iter, N_ITERACOES);
                    chartPanel.setHistory(historico);
                });
                
                if (it > 0) {
                    double melhoraRelativa = Math.abs((ultimaMelhorDistancia - melhorDistancia) / ultimaMelhorDistancia);
                    if (melhoraRelativa < LIMITE_MELHORA) {
                        semMelhoraCount++;
                    } else {
                        semMelhoraCount = 0;
                    }

                    if (semMelhoraCount >= MAX_SEM_MELHORA) {
                        System.out.println("\n>>> Parada antecipada: melhora abaixo de " + (LIMITE_MELHORA * 100)
                        		+ "% por " + MAX_SEM_MELHORA + " iteracoes consecutivas.");
                        break;
                    }
                }

                ultimaMelhorDistancia = melhorDistancia;

                // pausa para animação
                try { Thread.sleep(DELAY_MS); } catch (InterruptedException ignored) {}
            }
            if (paradaAntecipada) {
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(null,
                        String.format("Parada antecipada:\nMelhora inferior a %.2f%% por %d iterações consecutivas.",
                                LIMITE_MELHORA * 100, MAX_SEM_MELHORA),
                        "ACO - Convergência Detectada",
                        JOptionPane.INFORMATION_MESSAGE);
                });
            }
            return historico;
        }
    }

    // -----------------------
    // Painel de desenho do grafo
    // -----------------------

    static class GraphPanel extends JPanel {
        List<Ponto> pontos;
        List<Integer> bestRoute; // lista de índices
        double bestDist;
        double bestGap;
        int iteracao = 0, totalIter = 0;

        GraphPanel() {
            setPreferredSize(new Dimension(800, 600));
            setBackground(Color.WHITE);
        }

        public void setState(List<Ponto> pontos, List<Integer> bestRoute, double bestDist, double bestGap, int iter, int totalIter) {
            this.pontos = pontos;
            this.bestRoute = bestRoute;
            this.bestDist = bestDist;
            this.bestGap = bestGap;
            this.iteracao = iter;
            this.totalIter = totalIter;
            repaint();
        }

        // método auxiliar para mapear um ponto real para coordenada do painel
        private Point mapPoint(Ponto p, double minX, double spanX, double pad, double w, double h, double minY, double spanY) {
            int sx = (int) (pad + (p.x - minX) / spanX * w);
            int sy = (int) (pad + (1.0 - (p.y - minY) / spanY) * h); // y invertido
            return new Point(sx, sy);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (pontos == null) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // mapeia coordenadas reais para painel
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
            for (Ponto p : pontos) {
                minX = Math.min(minX, p.x);
                maxX = Math.max(maxX, p.x);
                minY = Math.min(minY, p.y);
                maxY = Math.max(maxY, p.y);
            }
            // margens
            double pad = 40;
            double w = getWidth() - 2 * pad;
            double h = getHeight() - 2 * pad;
            double spanX = Math.max(1e-6, maxX - minX);
            double spanY = Math.max(1e-6, maxY - minY);

            // desenha todas as arestas (linha fina cinza)
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(new Color(220, 220, 220));
            for (int i = 0; i < pontos.size(); ++i) {
                for (int j = i + 1; j < pontos.size(); ++j) {
                    Point pi = mapPoint(pontos.get(i), minX, spanX, pad, w, h, minY, spanY);
                    Point pj = mapPoint(pontos.get(j), minX, spanX, pad, w, h, minY, spanY);
                    g2.drawLine(pi.x, pi.y, pj.x, pj.y);
                }
            }

            // desenha a melhor rota (se existir)
            if (bestRoute != null && bestRoute.size() > 0) {
                g2.setColor(Color.RED);
                g2.setStroke(new BasicStroke(3f));
                for (int k = 0; k < bestRoute.size() - 1; ++k) {
                    Point a = mapPoint(pontos.get(bestRoute.get(k)), minX, spanX, pad, w, h, minY, spanY);
                    Point b = mapPoint(pontos.get(bestRoute.get(k + 1)), minX, spanX, pad, w, h, minY, spanY);
                    g2.drawLine(a.x, a.y, b.x, b.y);
                }
                // fechar ciclo
                Point a = mapPoint(pontos.get(bestRoute.get(bestRoute.size() - 1)), minX, spanX, pad, w, h, minY, spanY);
                Point b = mapPoint(pontos.get(bestRoute.get(0)), minX, spanX, pad, w, h, minY, spanY);
                g2.drawLine(a.x, a.y, b.x, b.y);
            }

            // desenha nós e labels (números 1..n)
            int r = 8;
            g2.setStroke(new BasicStroke(1f));
            for (int i = 0; i < pontos.size(); ++i) {
                Point p = mapPoint(pontos.get(i), minX, spanX, pad, w, h, minY, spanY);
                // nó
                Shape circle = new Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r);
                g2.setColor(Color.GREEN.darker());
                g2.fill(circle);
                g2.setColor(Color.BLACK);
                g2.draw(circle);
                // label numérico
                String label = String.valueOf(i + 1); // números a partir de 1
                g2.setColor(Color.BLUE.darker());
                g2.drawString(label, p.x + r + 2, p.y - r - 2);
            }

            // legenda / infos
            //g2.setColor(Color.BLACK);
            //g2.drawString("Iteração: " + iteracao + " / " + totalIter, 10, 15);
            //if (bestRoute != null) g2.drawString(String.format("Melhor distância: %.2f", bestDist), 10, 30);
              g2.setColor(Color.BLACK);
              g2.drawString("Iteração: " + iteracao + " / " + totalIter, 10, 15);
              if (bestRoute != null) {
                g2.drawString(String.format("Melhor distância: %.2f", bestDist), 10, 30);
                g2.drawString(String.format("GAP: %.2f%%", bestGap), 10, 45);
            }
        }
    }


    // -----------------------
    // Painel do gráfico de evolução
    // -----------------------
    static class ChartPanel extends JPanel {
        List<Double> history = new ArrayList<>();

        ChartPanel() {
            setPreferredSize(new Dimension(800, 180));
            setBackground(Color.WHITE);
        }

        void setHistory(List<Double> h) {
            this.history = new ArrayList<>(h);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (history == null || history.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int W = getWidth(), H = getHeight();
            int pad = 40;
            double max = Collections.max(history);
            double min = Collections.min(history);
            if (Math.abs(max - min) < 1e-6) { max = min + 1.0; }

            // eixos
            g2.setColor(Color.BLACK);
            g2.drawLine(pad, H - pad, W - pad, H - pad); // x
            g2.drawLine(pad, pad, pad, H - pad); // y

            // plot line
            int n = history.size();
            double plotW = W - 2 * pad;
            double plotH = H - 2 * pad;
            g2.setColor(Color.BLUE);
            g2.setStroke(new BasicStroke(2f));
            for (int i = 0; i < n - 1; ++i) {
                double v1 = (history.get(i) - min) / (max - min);
                double v2 = (history.get(i + 1) - min) / (max - min);
                int x1 = (int) (pad + (i / (double)(N_ITERACOES - 1)) * plotW);
                int x2 = (int) (pad + ((i + 1) / (double)(N_ITERACOES - 1)) * plotW);
                int y1 = (int) (pad + (1 - v1) * plotH);
                int y2 = (int) (pad + (1 - v2) * plotH);
                g2.drawLine(x1, y1, x2, y2);
                // ponto
                g2.fillOval(x1 - 3, y1 - 3, 6, 6);
            }
            // último ponto
            double lastV = (history.get(n - 1) - min) / (max - min);
            int xl = (int) (pad + ((n - 1) / (double)(N_ITERACOES - 1)) * plotW);
            int yl = (int) (pad + (1 - lastV) * plotH);
            g2.fillOval(xl - 3, yl - 3, 6, 6);

            // labels
            g2.setColor(Color.BLACK);
            g2.drawString("Iterações", W/2 - 20, H - 8);
            g2.drawString("Distância (melhor)", 8, 12);

            // valores nos eixos (min e max)
            g2.drawString(String.format("%.3f", min), 5, H - pad);
            g2.drawString(String.format("%.3f", max), 5, pad + 8);
        }
    }

    // -----------------------
    // Leitura CSV simples
    // -----------------------
    static List<Ponto> carregarCSV(Path caminho) throws IOException {
        List<String> linhas = Files.readAllLines(caminho);
        List<Ponto> pts = new ArrayList<>();
        // assume header "x,y"
        boolean first = true;
        for (String l : linhas) {
            if (l.trim().isEmpty()) continue;
            if (first) { first = false; continue; }
            String[] parts = l.split(",");
            if (parts.length < 2) continue;
            double x = Double.parseDouble(parts[0].trim());
            double y = Double.parseDouble(parts[1].trim());
            pts.add(new Ponto(x, y));
        }
        return pts;
    }

    // -----------------------
    // MAIN - GUI + execução
    // -----------------------
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("ACO Visualizer - Java");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());

            GraphPanel graphPanel = new GraphPanel();
            ChartPanel chartPanel = new ChartPanel();

            frame.add(graphPanel, BorderLayout.CENTER);
            frame.add(chartPanel, BorderLayout.SOUTH);

            frame.setSize(1000, 820);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            // Escolher arquivo CSV via file chooser
            Path csvPath = null;
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Selecione o arquivo CSV (colunas: x,y)");
            int res = chooser.showOpenDialog(frame);
            if (res == JFileChooser.APPROVE_OPTION) {
                csvPath = chooser.getSelectedFile().toPath();
            } else {
                // fallback para PATH padrão (se existir)
                Path p = Paths.get(DEFAULT_CSV);
                if (Files.exists(p)) csvPath = p;
            }

            if (csvPath == null) {
                JOptionPane.showMessageDialog(frame, "Nenhum arquivo selecionado e arquivo padrão nao encontrado.\nEncerrando.", "Erro", JOptionPane.ERROR_MESSAGE);
                System.exit(0);
            }

            // Leitura dos pontos
            List<Ponto> pontos;
            try {
                pontos = carregarCSV(csvPath);
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(frame, "Erro lendo CSV: " + e.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (pontos.size() < 2) {
                JOptionPane.showMessageDialog(frame, "CSV deve conter pelo menos 2 pontos.", "Erro", JOptionPane.ERROR_MESSAGE);
                return;
            }

            

            // Inicia ACO em thread separada
            ACO aco = new ACO(pontos);
            Thread worker = new Thread(() -> {
                List<Double> hist = aco.runAndReport(graphPanel, chartPanel);
                // após terminar, exibe diálogo
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(frame, "Execução terminada.", "Concluído", JOptionPane.INFORMATION_MESSAGE);
                });
            });
            worker.start();
        });
    }
}
    
