package com.github.javactic.futures;

import com.github.javactic.Bad;
import com.github.javactic.Every;
import com.github.javactic.Fail;
import com.github.javactic.Good;
import com.github.javactic.One;
import com.github.javactic.Or;
import com.github.javactic.Pass;
import com.github.javactic.Validation;
import javaslang.collection.Seq;
import javaslang.collection.Vector;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


@RunWith(Theories.class)
public class AccumulationTest {

  @DataPoints
  public static ExecutorService[] configs = {Executors.newSingleThreadExecutor(), ForkJoinPool.commonPool()};

  @Test
  public void withGoodFail() throws Exception {
    OrFuture<String, One<String>> success = OrFuture.ofGood("good");
    OrFuture<String, One<String>> fail = OrFuture.ofOneBad("bad");
    OrFuture<String, Every<String>> result = OrFuture.withGood(success, fail, (a1, a2) -> "doesn't matter");
    Or<String, Every<String>> or = result.result(Duration.ofSeconds(10));
    assertEquals("bad", or.getBad().head());
  }

  @Test
  public void withGoodSuccess() throws Exception {
    OrFuture<String, One<String>> s1 = OrFuture.ofGood("A");
    OrFuture<Integer, One<String>> s2 = OrFuture.ofGood(1);
    OrFuture<String, Every<String>> result = OrFuture.withGood(s1, s2, (a1, a2) -> a1 + a2);
    Or<String, Every<String>> or = result.result(Duration.ofSeconds(10));
    assertEquals("A1", or.get());
  }

  @Theory
  public void sequenceSuccess(ExecutorService es) throws Exception {
    Seq<OrFuture<Integer, Every<String>>> seq = Vector.empty();
    for (int i = 0; i < 10; i++) {
      final int fi = i;
      seq = seq.append(OrFuture.of(es, () -> Good.of(fi)));
    }
    OrFuture<Vector<Integer>, Every<String>> sequence = OrFuture.combined(seq);
    Or<Vector<Integer>, Every<String>> or = sequence.result(Duration.ofSeconds(10));
    Assert.assertTrue(or.isGood());
    String fold = or.get().foldLeft("", (s, i) -> s + i);
    assertEquals("0123456789", fold);
  }

  @Theory
  public void sequenceFailure(ExecutorService es) throws Exception {
    Seq<OrFuture<Integer, Every<Integer>>> seq = Vector.empty();
    for (int i = 0; i < 10; i++) {
      final int fi = i;
      if (i % 2 == 0)
        seq = seq.append(OrFuture.of(es, () -> Good.of(fi)));
      else
        seq = seq.append(OrFuture.of(es, () -> Bad.ofOne(fi)));
    }
    OrFuture<Vector<Integer>, Every<Integer>> sequence = OrFuture.combined(seq);
    Or<Vector<Integer>, Every<Integer>> or = sequence.result(Duration.ofSeconds(10));
    Assert.assertTrue(or.isBad());
    String fold = or.getBad().foldLeft("", (s, i) -> s + i);
    assertEquals("13579", fold);
  }

  @Test
  public void combinedSecondFinishesFirst() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    OrFuture<String, String> f1 = OrFuture.of(() -> {
      try {
        latch.await();
      } catch (Exception e) {
        Assert.fail();
      }
      return Good.of("1");
    });
    OrFuture<String, String> f2 = OrFuture.of(() -> Good.of("2"));
    OrFuture<Vector<String>, Every<String>> combined = OrFuture.combined(Vector.of(f1.accumulating(), f2.accumulating()));
    f2.onComplete(or -> latch.countDown());
    Or<Vector<String>, Every<String>> or = combined.result(Duration.ofSeconds(10));
    Assert.assertTrue(or.isGood());
    String fold = or.get().foldLeft("", (s, i) -> s + i);
    assertEquals("12", fold);
  }

  @Test
  public void combinedSecondFinishesLast() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    OrFuture<String, String> f1 = OrFuture.of(() -> Good.of("1"));
    OrFuture<String, String> f2 = OrFuture.of(() -> {
      try {
        latch.await();
      } catch (Exception e) {
        Assert.fail();
      }
      return Good.of("2");
    });
    OrFuture<Vector<String>, Every<String>> combined = OrFuture.combined(Vector.of(f1.accumulating(), f2.accumulating()));
    f1.onComplete(or -> latch.countDown());
    Or<Vector<String>, Every<String>> or = combined.result(Duration.ofSeconds(10));
    Assert.assertTrue(or.isGood());
    String fold = or.get().foldLeft("", (s, i) -> s + i);
    assertEquals("12", fold);
  }

  @Theory
  public void validatedBy(ExecutorService es) throws InterruptedException, ExecutionException, TimeoutException {
    Vector<Integer> vec = Vector.of(1,2,3);
    Function<Integer, OrFuture<Integer, Every<String>>> f = i ->
      OrFuture.of(es, () -> {
        if(i < 10) return Good.of(i);
        else return Bad.of(One.of("wasn't under 10"));
      });
    Or<Vector<Integer>, Every<String>> res = OrFuture.validatedBy(vec, f).result(Duration.ofSeconds(10));
    assertTrue(res.isGood());
    assertEquals(vec, res.get());
    res = OrFuture.validatedBy(Vector.of(11), f, Vector.collector()).result(Duration.ofSeconds(10));
    assertTrue(res.isBad());
    assertTrue(res.getBad() instanceof One);
  }

  @Theory
  public void when(ExecutorService es) throws InterruptedException, ExecutionException, TimeoutException {
    Function<String, Validation<String>> f1 = f -> f.startsWith("s") ? Pass.instance() : Fail.of("does not start with s");
    Function<String, Validation<String>> f2 = f -> f.length() > 4 ? Fail.of("too long") : Pass.instance();
    OrFuture<String, One<String>> orFuture = OrFuture.of(es, () -> Bad.ofOne("bad"));
    OrFuture<String, Every<String>> res = OrFuture.when(orFuture, f1, f2);
    assertEquals("bad", res.result(Duration.ofSeconds(10)).getBad().get(0));
    orFuture = OrFuture.of(es, () -> Good.of("sub"));
    res = OrFuture.when(orFuture, f1, f2);
    assertTrue(res.result(Duration.ofSeconds(10)).isGood());
    orFuture = OrFuture.of(es, () -> Good.of("fubiluuri"));
    res = OrFuture.when(orFuture, f1, f2);
    assertTrue(res.result(Duration.ofSeconds(10)).isBad());
  }

  @Theory
  public void withGood(ExecutorService es) throws Exception {
    Function<? super OrFuture<String, ? extends Every<String>>[], OrFuture<?, Every<String>>> fun =
      ors -> OrFuture.withGood(ors[0], ors[1], (a, b) -> "");
    testWithF(es, fun, 2);
    fun = o -> OrFuture.withGood(o[0], o[1], o[2], (a, b, c) -> "");
    testWithF(es, fun, 3);
    fun = o -> OrFuture.withGood(o[0], o[1], o[2], o[3], (a, b, c, d) -> "");
    testWithF(es, fun, 4);
    fun = o -> OrFuture.withGood(o[0], o[1], o[2], o[3], o[4], (a, b, c, d, e) -> "");
    testWithF(es, fun, 5);
    fun = o -> OrFuture.withGood(o[0], o[1], o[2], o[3], o[4], o[5], (a, b, c, d, e, f) -> "");
    testWithF(es, fun, 6);
    fun = o -> OrFuture.withGood(o[0], o[1], o[2], o[3], o[4], o[5], o[6], (a, b, c, d, e, f, g) -> "");
    testWithF(es, fun, 7);
    fun = o -> OrFuture.withGood(o[0], o[1], o[2], o[3], o[4], o[5], o[6], o[7], (a, b, c, d, e, f, g, h) -> "");
    testWithF(es, fun, 8);
  }

  @Theory
  public void zips(ExecutorService es) throws Exception {
    Function<? super OrFuture<String, ? extends Every<String>>[], OrFuture<?, Every<String>>> fun =
      ors -> OrFuture.zip(ors[0], ors[1]);
    testWithF(es, fun, 2);
    fun = o -> OrFuture.zip3(o[0], o[1], o[2]);
    testWithF(es, fun, 3);
    fun = o -> OrFuture.zip4(o[0], o[1], o[2], o[3]);
    testWithF(es, fun, 4);
    fun = o -> OrFuture.zip5(o[0], o[1], o[2], o[3], o[4]);
    testWithF(es, fun, 5);
    fun = o -> OrFuture.zip6(o[0], o[1], o[2], o[3], o[4], o[5]);
    testWithF(es, fun, 6);
    fun = o -> OrFuture.zip7(o[0], o[1], o[2], o[3], o[4], o[5], o[6]);
    testWithF(es, fun, 7);
    fun = o -> OrFuture.zip8(o[0], o[1], o[2], o[3], o[4], o[5], o[6], o[7]);
    testWithF(es, fun, 8);

  }

  private void testWithF(ExecutorService es,
                         Function<? super OrFuture<String, ? extends Every<String>>[], OrFuture<?, Every<String>>> f,
                         int size) throws Exception {
    @SuppressWarnings("unchecked")
    OrFuture<String, One<String>>[] ors = new OrFuture[size];
    for (int i = 0; i <= ors.length; i++) {
      for (int j = 0; j < ors.length; j++) {
        if (j == i) ors[j] = OrFuture.of(es, () -> Bad.ofOne("bad"));
        else ors[j] = OrFuture.of(es, () -> Good.of("good"));
      }
      OrFuture<?, Every<String>> val = f.apply(ors);
      if (i < ors.length)
        assertTrue(val.result(Duration.ofSeconds(10)).isBad());
      else
        assertTrue(val.result(Duration.ofSeconds(10)).isGood());
    }
  }

  @Test
  public void constructorsForCoverage() throws Exception {
    Constructor<Helper> constructor = Helper.class.getDeclaredConstructor();
    assertTrue(Modifier.isPrivate(constructor.getModifiers()));
    constructor.setAccessible(true);
    constructor.newInstance();
  }

}
