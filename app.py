import streamlit as st
import requests
import json

# 1. PASTE YOUR API GATEWAY URL HERE
API_URL = "https://uvgbgvrkcg.execute-api.us-east-1.amazonaws.com/dev/query"

st.title("Text2SQL Sprint 1")

# 2. Input Box
query = st.text_input("Ask a question:", "Show me total sales")

# 3. Button
if st.button("Run Query"):
    with st.spinner("Agents are working..."):
        try:
            # Send data to AWS
            payload = {"user_query": query}
            response = requests.post(API_URL, json=payload)

            # Parse AWS response
            data = response.json()

            # Check if 'output' exists (Step Functions success)
            if 'output' in data:
                # The 'output' is a stringified JSON, so we parse it again
                final_state = json.loads(data['output'])

                st.success("Success!")

                st.subheader("Generated SQL")
                st.code(final_state.get('generated_sql'), language='sql')

                st.subheader("Data Result")
                st.write(final_state.get('execution_result'))

                with st.expander("Debug Agent State"):
                    st.json(final_state)
            else:
                st.error(f"Error from AWS: {data}")

        except Exception as e:
            st.error(f"Connection Error: {e}")