# Relatório Técnico de Refatoração: SaaS Billing Co.

**Disciplina:** Implantação e Entrega de Software – 2026/2  
**Instituição:** Instituto de Informática (INF) – Universidade Federal de Goiás (UFG)  
**Professora:** Sofia Costa Paiva  
**Curso:** Engenharia de Software  
**Alunos / Dupla:** Gabriel Rodrigues da Silva / Matheus Fagundes Soares

## 1. Matriz de Code Smells e Violações Arquiteturais (Diagnóstico de Débito Técnico)

Abaixo está o mapeamento detalhado dos problemas estruturais identificados no código legado (`SubscriptionBillingService`):

| # | Linha/Trecho Afetado | Code Smell / Princípio Violado | Descrição do Problema | Impacto Prático ("Juros Técnicos") na Manutenibilidade e CI/CD |
|---|----------------------|--------------------------------|-----------------------|-----------------------------------------------------------------|
| **1** | Linhas 18–20, 29, 50–57 | **Primitive Obsession** & **Long Parameter List (Data Clumps)** | O método `processMonthlyBilling` recebia 8 parâmetros soltos e utilizava o tipo primitivo `double` para operações financeiras (`basePrice`, `calculatedPrice`, `penalty`, `dailyInterest`). | • Risco de inversão de argumentos do mesmo tipo (Strings consecutivas).<br>• Erros cumulativos de ponto flutuante (IEEE 754) gerando inconsistências de centavos e prejuízos contábeis.<br>• Regras de arredondamento espalhadas pelo código sem padrão único. |
| **2** | Linhas 18–105 | **Long Method** & **God Method** / Violação do **Single Responsibility Principle (SRP)** | O método orquestrador concentrava regras de desconto de negócio, chamadas SQL JDBC diretas, processamento de pagamento e montagem/envio de e-mails em um único bloco monolítico. | • Baixa coesão e altíssimo risco de regressão: alterar uma regra de desconto pode quebrar a persistência no banco.<br>• Impossibilidade de testar a lógica pura de precificação sem executar I/O. |
| **3** | Linhas 14–16, 59–64, 82–97 | **Dependência Direta de Infraestrutura / I/O** & Violação da **Inversão de Dependência (DIP)** | Acoplamento rígido através de chamadas estáticas `DriverManager.getConnection(...)` e instanciação direta de `new SimpleEmail()`. | • Bloqueio da esteira de CI/CD: testes unitários falham caso não haja um PostgreSQL ou servidor SMTP ativos.<br>• Testes tornam-se lentos, instáveis (*flaky tests*) e dependentes de estado de banco externo. |
| **4** | Linhas 25, 42 | **Falta de Determinismo Temporal** (Acoplamento ao Relógio Estático do SO) | Uso estático de `LocalDate.now()` para calcular tempo de atividade e checar o mês de vigência da promoção de Black Friday (`today.getMonthValue() == 11`). | • Falhas de teste sensíveis à data de execução: um teste da promoção Black Friday só passará no mês de novembro.<br>• Dificuldade em simular cenários futuros ou passados de forma reproduzível. |
| **5** | Linhas 100–102 | **Tratamento Genérico de Exceções** (Catch-All / *Swallowed Exception*) | Bloco `catch (Exception e)` capturando qualquer falha sem distinção, apenas adicionando uma mensagem à lista de logs e prosseguindo. | • Falhas silenciosas em produção: erros de SQL ou rede passam desapercebidos pelo chamador.<br>• Perda do stack trace original, impossibilitando depuração ágil e geração de alertas na monitoria. |
| **6** | Linha 96 | **Código Morto / Comentado** (*Dead Code*) | A chamada `// emailSender.send();` permaneceu comentada no fluxo principal. | • Gera dúvida na equipe de manutenção se o e-mail deveria ou não ser disparado.<br>• Poluição visual e ruído cognitivo no código de produção. |

---

## 2. Registro da Refatoração por Etapas

### Tópico 1: Eliminação de Primitive Obsession e Imutabilidade (Concluído)

#### 1. Criação do Value Object `Money`
* **Implementação:** Desenvolvido como um `record` imutável em Java (`Money.java`).
* **Responsabilidades Encapsuladas:**
  * Uso de `BigDecimal` com escala fixa de 2 casas decimais e arredondamento `RoundingMode.HALF_UP`.
  * Operações financeiras com segurança semântica: `add(Money)`, `subtract(Money)`, `multiply(double|BigDecimal)`, `percentage(double)`.
  * Comparações e validações: `isNegative()`, `isZero()`, `isPositive()`, `isLessThan(Money)`, `isGreaterThan(Money)`, `compareTo(Money)`.
  * Conversão segura para persistência (`doubleValue()`, `amount()`) e formatação textual legível (`toString()`).

#### 2. Criação do Command / Parameter Object `BillingCommand`
* **Implementação:** Desenvolvido como um `record` em Java (`BillingCommand.java`).
* **Responsabilidades Encapsuladas:**
  * Reúne os parâmetros de entrada: `customerId`, `planType`, `basePrice` (agora fortemente tipado como `Money`), `signupDateStr`, `overdueDays`, `promoCode`, `isNonProfit` e `paymentCardToken`.
  * Validações de não-nulidade na construção para garantir consistência.
  * Construtor de conveniência aceitando `double basePrice` para manter compatibilidade e facilitar a transição.

#### 3. Ajuste em `SubscriptionBillingService`
* A assinatura de `processMonthlyBilling` foi refatorada para receber diretamente `BillingCommand command`.
* As variáveis monetárias intermediárias (`calculatedPrice`, `penalty`, `dailyInterest`) passaram a operar com instâncias de `Money`, garantindo que todas as deduções, acréscimos e arredondamentos ocorram de forma encapsulada.
* Foi mantida uma sobrecarga do método original anotada como `@Deprecated`, delegando internamente para a nova sobrecarga via `BillingCommand`, assegurando total retrocompatibilidade.

---

## 4. Tabela Comparativa de Métricas (Antes vs. Depois)

| Métrica / Dimensão Analisada | Antes da Refatoração (Legado) | Depois do Tópico 1 | Status Final Previsto (Pós Tópicos 2, 3 e 4) |
|------------------------------|-------------------------------|--------------------|----------------------------------------------|
| **Número de parâmetros do método orquestrador** | 8 parâmetros soltos (Strings, doubles, ints, booleans) | **1 parâmetro** (`BillingCommand`) | 1 parâmetro (`BillingCommand`) |
| **Tipo representativo de moeda/valores financeiros** | `double` primitivo sujeito a erros de precisão IEEE 754 | **Value Object imutável `Money`** (`BigDecimal` escala 2, `HALF_UP`) | Value Object `Money` |
| **Encapsulamento de operações financeiras** | Disperso em expressões aritméticas (`* 0.15`, `- 30.0`, etc.) | **Centralizado em `Money`** (`.percentage()`, `.subtract()`, etc.) | Centralizado em `Money` |
| **Acoplamento com I/O no fluxo de faturamento** | Acoplamento rígido direto a PostgreSQL e SMTP | Mantido temporariamente (aguardando Tópico 2) | Desacoplado via interfaces (`CustomerRepository`, `PaymentGateway`, `NotificationService`) |
| **Determinismo temporal de testes** | Indeterminado (dependente de `LocalDate.now()`) | Mantido temporariamente (aguardando Tópico 2) | 100% determinístico via `Clock` injetado |
| **Complexidade ciclomática e tamanho do orquestrador** | Monólito com ~88 linhas de método | ~85 linhas (lógica de cálculo mantida in-line) | Fachada de alto nível com no máximo 25 linhas |
| **Tratamento de exceções** | Genérico (`catch (Exception e)`) mascarando erros | Mantido temporariamente (aguardando Tópico 4) | Tratamento especializado e propagação correta |

