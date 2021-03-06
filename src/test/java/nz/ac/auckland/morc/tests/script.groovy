import nz.ac.auckland.morc.MorcTestBuilder
import nz.ac.auckland.morc.morc

morc.run(new MorcTestBuilder() {
    public void configure() {
        syncTest("Simple Echo Test", "http://echo.jsontest.com/foo/baz")
                .expectation(json('{ "foo":"baz" }'))
    }
})

new MorcTestBuilder() {
    public void configure() {
        syncTest("Simple Echo Test", "http://echo.jsontest.com/foo/baz")
                .expectation(json('{ "foo":"baz" }'))

        syncTest("Simple Echo Test", "http://echo.jsontest.com/foo/baz")
                .expectation(json('{ "foo":"baz" }'))
    }
}.run()